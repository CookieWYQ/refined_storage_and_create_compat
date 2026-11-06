#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 132 轮「发送器界面」自检（只读源码，不启动游戏）。

对应用户 2026-11-06 语音反馈与 docs/ROUND132_CONTRACT.md「一、发送器」：
  ① 发送模式左侧模式页签与 RS 侧键重叠 ⇒ 页签列 x = leftPos - TAB_W - 18 - 4（x<2 取 2）；
  ② 手写数量输入框 / ± 按钮删除 ⇒ 点订单行内数量文字打开 RS 原生
     AbstractAmountScreen 子界面，确认走 RequesterOrderPacket.ACTION_SET_AMOUNT；
  ③ 发送器界面隐藏 JEI ⇒ ResourcePickerJeiPlugin 把整屏报成 GUI 额外占用区；
  ④ 面板深色底上的文字颜色全部换成浅色；
  ⑤ 面板行高随数量控件删除一起回收（不再有 AMOUNT_ROW_H / amountY()）。

用法：python tools/selfcheck_round132_sender.py
退出码：0 = 全部 PASS。
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
PKG = os.path.join("src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
SCREEN = os.path.join(PKG, "client", "grid", "ResourcePickerScreen.java")
AMOUNT = os.path.join(PKG, "client", "grid", "OrderAmountScreen.java")
OVERLAY = os.path.join(PKG, "client", "TerminalModeTabOverlay.java")
JEI = os.path.join(PKG, "client", "ResourcePickerJeiPlugin.java")

PASSED = 0
FAILED = []


def read(rel):
    path = os.path.join(ROOT, rel)
    with io.open(path, encoding="utf-8") as handle:
        return handle.read()


def collapse(text):
    return re.sub(r"\s+", "", text)


def body(text, pattern):
    """取方法体（从签名到下一个同缩进的 `}`），找不到返回 None。"""
    match = re.search(pattern, text)
    if not match:
        return None
    start = match.start()
    depth = 0
    started = False
    for index in range(start, len(text)):
        ch = text[index]
        if ch == "{":
            depth += 1
            started = True
        elif ch == "}":
            depth -= 1
            if started and depth == 0:
                return text[start:index + 1]
    return None


def check(name, fn):
    global PASSED
    try:
        detail = fn()
    except AssertionError as exc:
        FAILED.append((name, str(exc)))
        print("FAIL %s -> %s" % (name, exc))
        return
    except Exception as exc:  # 断言函数自身异常也算失败，不能静默跳过
        FAILED.append((name, "断言异常：%r" % (exc,)))
        print("FAIL %s -> 断言异常：%r" % (name, exc))
        return
    PASSED += 1
    print("PASS %s %s" % (name, detail))


# ======================================================================================
# ① 页签几何：避开 RS 侧键（x ∈ [leftPos-20, leftPos-2]）
# ======================================================================================
def tab_geometry():
    text = read(OVERLAY)
    compact = collapse(text)
    assert "import com.refinedmods.refinedstorage.common.support.widget.AbstractSideButtonWidget;" in text, \
        "没有引用 RS 的 AbstractSideButtonWidget.SIZE"
    assert collapse("containerScreen.getGuiLeft() - TAB_W - AbstractSideButtonWidget.SIZE - 4") in compact, \
        "发送模式页签 x 不是 leftPos - TAB_W - AbstractSideButtonWidget.SIZE - 4（= leftPos-46）"
    assert collapse("Math.max(SCREEN_MARGIN,") in compact, \
        "页签 x 没有按 x<2 取 SCREEN_MARGIN(=2) 的兜底"
    assert collapse("getGuiLeft() - TAB_W - SCREEN_MARGIN") not in compact, \
        "旧的 leftPos - TAB_W - 2 公式仍在（会与 RS 侧键重叠）"
    # 纵向仍贴 GUI 底部：columnTop 公式不变
    assert collapse("columnTop = clamp(containerScreen.getGuiTop() + guiH - columnH") in compact, \
        "页签纵向不再贴 GUI 底部（列顶公式被改动）"
    return "左移 18+4，右缘与侧键左缘留 2px，x<2 时取 2"


# ======================================================================================
# ② 手写数量控件：零残留 + 新入口
# ======================================================================================
def amount_controls_removed():
    text = read(SCREEN)
    stale = []
    for name, pattern in {
        "amountBox": r"\bamountBox\b",
        "minusButton": r"\bminusButton\b",
        "plusButton": r"\bplusButton\b",
        "buildAmountControls": r"\bbuildAmountControls\b",
        "applyAmount": r"\bapplyAmount\b",
        "stepAmount": r"\bstepAmount\b",
        "parseAmount": r"\bparseAmount\b",
        "currentAmount": r"\bcurrentAmount\b",
        "amountLoadedKey": r"\bamountLoadedKey\b",
        "parsedAmount": r"\bparsedAmount\b",
        "AMOUNT_ROW_H": r"\bAMOUNT_ROW_H\b",
        "amountY": r"\bamountY\b",
        "renderAmountTooltip": r"\brenderAmountTooltip\b",
    }.items():
        if re.search(pattern, text):
            stale.append(name)
    assert not stale, "手写数量控件 / 布局残留：" + "、".join(stale)
    # Enter 应用分支随 amountBox 一起删除
    assert "applyAmount" not in text and "amountBox" not in text, "keyPressed 的 Enter 分支可能残留"
    # 面板固定高度不再包含数量行
    assert "final int fixed = HEADER_H + FILTER_H + GAP + MESSAGE_H + GAP + PANEL_PAD * 2;" in text, \
        "layoutPanel 仍为数量行留高度（面板会留空行）"
    assert "return rowsTop() + panelCapacity * ROW_H + GAP;" in text, \
        "messageY() 没有上移到行区之后（面板高度没回收）"
    return "输入框 / ± / 解析 / 回车分支 / 数量行高全部删除，面板行高回收"


def amount_entry_and_packet():
    text = read(SCREEN)
    open_body = body(text, r"private void openAmountScreen\(final int index\)")
    assert open_body is not None, "找不到 openAmountScreen"
    assert "new OrderAmountScreen(this, initial, max, value -> sendSetAmount(index, value))" in open_body, \
        "行内数量文字没有打开 OrderAmountScreen（或用错了确认回调）"
    click = body(text, r"public boolean mouseClicked\(")
    assert click is not None and "isOverAmountText(mouseX, index)" in click, \
        "行点击没有区分「数量文字区」与其它区域"
    assert "focusRow(index);" in click, "行其它区域左键不再执行选中"
    send = body(text, r"private void sendSetAmount\(final int index, final long amount\)")
    assert send is not None, "找不到 sendSetAmount"
    assert "RequesterOrderPacket.ACTION_SET_AMOUNT" in send, "确认后没有发 ACTION_SET_AMOUNT"
    assert "index, \"\", row.fluid()," in send, "ACTION_SET_AMOUNT 没有用权威下标 / 流体标记"
    assert "RsccNumberField.clampToInt(Math.max(1L, amount))" in send, \
        "数量没有做 [1, int] 夹取保护（超过 Integer.MAX_VALUE 会截断）"
    assert "row.key()" in send, "ACTION_SET_AMOUNT 没有带完整资源键做二次确认"
    return "行内数量文字 → OrderAmountScreen → ACTION_SET_AMOUNT(int 夹取)"


# ======================================================================================
# ③ RS 原生数量子界面
# ======================================================================================
def order_amount_screen():
    text = read(AMOUNT)
    compact = collapse(text)
    assert "extends AbstractAmountScreen<OrderAmountScreen.DummyMenu, Long>" in text, \
        "OrderAmountScreen 没有继承 RS 的 AbstractAmountScreen<..., Long>"
    assert re.search(r"DummyMenu\s+extends\s+DefaultDummyContainerMenu", text), \
        "dummy 菜单没有复用 RS 的 AbstractAmountScreen.DefaultDummyContainerMenu"
    assert "LongAmountOperations.INSTANCE" in text, "没有使用 RS 的 LongAmountOperations.INSTANCE"
    for token in (
        "withInitialAmount(start)",
        "withIncrementsTop(1, 10, 64)",
        "withIncrementsBottom(-1, -10, -64)",
        "withIncrementsBottomStartPosition(new Vector3f(7, 72, 0))",
        "withAmountFieldPosition(new Vector3f(9, 51, 0))",
        "withActionButtonsStartPosition(new Vector3f(114, 22, 0))",
        "withMinAmount(() -> 1L)",
        "withMaxAmount(max)",
        "withResetAmount(1L)",
    ):
        assert collapse(token) in compact, "数量子界面配置缺少：" + token
    assert "refinedstorage\", \"textures/gui/resource_amount.png" in text, \
        "没有使用 RS 的 resource_amount.png 贴图"
    assert "this.imageWidth = 180;" in text and "this.imageHeight = 99;" in text, \
        "子界面尺寸不是 180×99（RS 贴图会错位）"
    confirm = body(text, r"protected boolean confirm\(final Long amount\)")
    assert confirm is not None, "找不到 confirm(Long)"
    assert "onConfirm.accept(amount);" in confirm and "return true;" in confirm, \
        "confirm 没有回调父界面并返回 true（子界面不会自动关回 parent）"
    return "AbstractAmountScreen<DummyMenu, Long> + RS 配置 / 贴图 / confirm 回 parent"


# ======================================================================================
# ④ JEI 隐藏
# ======================================================================================
def jei_hidden():
    text = read(JEI)
    compact = collapse(text)
    assert "@JeiPlugin" in text and "implements IModPlugin" in text, "不是 @JeiPlugin / IModPlugin"
    assert collapse("registration.addGuiContainerHandler(ResourcePickerScreen.class") in compact, \
        "没有为 ResourcePickerScreen 注册 IGuiContainerHandler"
    assert collapse("List.of(new Rect2i(0, 0, screen.width, screen.height))") in compact, \
        "getGuiExtraAreas 没有把整屏报成 GUI 占用区（JEI 不会收起）"
    return "整屏 Rect2i 占用 ⇒ JEI 无空间，自动收起"


# ======================================================================================
# ⑤ 颜色
# ======================================================================================
def panel_colors():
    text = read(SCREEN)
    expected = {
        "COLOR_TITLE": "0xFFFFE08A",
        "COLOR_TEXT": "0xFFE8E8E8",
        "COLOR_HINT": "0xFFAFC8E8",
        "COLOR_WARN": "0xFFFF8A8A",
        "COLOR_OK": "0xFF8FE08F",
        "COLOR_FLUID": "0xFF7FC8FF",
        "COLOR_DELETE": "0xFFFF9A9A",
    }
    for name, value in expected.items():
        assert re.search(r"%s\s*=\s*%s\s*;" % (name, value), text), \
            "%s 不是 %s（深底上不可读）" % (name, value)
    for old in ("0xFF333333", "0xFF404040", "0xFF1F4E79", "0xFF9B2020", "0xFF1E6B2E", "0xFF1F6FB0", "0xFF7A2020"):
        assert old not in text, "旧深色字值仍残留：" + old
    assert "COLOR_ROW_SELECTED = 0x3300A0FF;" in text, "行选中底色被改动"
    assert "COLOR_SELECTED_FILL = 0x55FFC107;" in text and "COLOR_SELECTED_BORDER = 0xFF2E7D32;" in text, \
        "网格键高亮色被改动"
    # 工具条速率文字必须走可读的 COLOR_TEXT
    rate = body(text, r"private void renderToolbarText\(")
    assert rate is not None and "COLOR_TEXT" in rate, "工具条速率文字没有用 COLOR_TEXT"
    return "标题浅金 / 正文近白 / 提示浅蓝 / 警告红 / 成功绿 / 流体蓝"


CHECKS = [
    ("① 页签避开 RS 侧键", tab_geometry),
    ("② 手写数量控件零残留", amount_controls_removed),
    ("②' 行内数量文字 → RS 子界面 → SET_AMOUNT", amount_entry_and_packet),
    ("③ OrderAmountScreen 复用 RS AbstractAmountScreen", order_amount_screen),
    ("④ 发送器界面隐藏 JEI", jei_hidden),
    ("⑤ 面板文字颜色", panel_colors),
]


def main():
    print("=" * 78)
    print("第 132 轮发送器自检：页签让位 / RS 数量子界面 / 隐藏 JEI / 浅色文字")
    print("=" * 78)
    for name, fn in CHECKS:
        check(name, fn)
    print("-" * 78)
    if FAILED:
        print("[result] 自检失败：%d / %d 项未通过" % (len(FAILED), len(CHECKS)))
        return 1
    print("[result] 自检通过：%d 项断言" % PASSED)
    return 0


if __name__ == "__main__":
    sys.exit(main())
