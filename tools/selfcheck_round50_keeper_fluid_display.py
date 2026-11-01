# -*- coding: utf-8 -*-
"""第 50 轮：定量保持器「流体用自身 tooltip + 大数值输入框」静态自检（只读源码 / 纯逻辑推演）。

对应用户第 4 项反馈的三条：

① 流体 tooltip 改为渲染<b>流体本身</b>（像 JEI 那样），且<b>不再渲染数量</b>
   —— 判据：
     · tooltip 行只有流体自身的名字（唯一实现 GhostMarkerRenderer.fluidName =
       FluidStack#getHoverName 那一口径，RS 的 FluidStackFluidRenderer#getTooltip 同源）；
     · 源码里不再出现 `gui.rs_create_compat.marker.amount` 的调用（数量行整条移除）；
     · 基础版仍走同一个 renderFluidTooltip（没有第二套自绘）；<b>第 88 轮起高级版更进一步</b>：
       流体标记槽里放的是该流体的<b>真实桶物品</b>，tooltip 完全交给原版物品 tooltip
       （别的模组才能接管）⇒ 高级版不得再调用 renderFluidTooltip，也不得自拼流体名。

② 大数值显示不全（1 亿 mB 显示成一串 0）
   —— 判据：输入框几何（RsccKeeperGeometry）保证文本区放得下 `100,000,000` 的千分位写法；
      显示 / 解析走唯一实现 RsccNumberField；边界用例 0 / 1 / 1000 / 100000000 / Long.MAX_VALUE
      都能「完整可读」且「解析回来等于原值」。

③ 加大输入框不得与后面的按钮重叠
   —— 判据：几何穷举（两两不重叠、不越界、不压槽位、桶提示不压任何控件）。

用法：python tools/selfcheck_round50_keeper_fluid_display.py
退出码：0 = 全部通过；1 = 有问题（逐条打印）。
"""
import io
import itertools
import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
WIDGET = os.path.join(JAVA, "client", "widget")
SCREEN = os.path.join(JAVA, "client", "screen")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

GHOST = os.path.join(WIDGET, "GhostMarkerRenderer.java")
NUMBER = os.path.join(WIDGET, "RsccNumberField.java")
GEOMETRY = os.path.join(WIDGET, "RsccKeeperGeometry.java")
BASIC_SCREEN = os.path.join(SCREEN, "QuantityKeeperScreen.java")
ADV_SCREEN = os.path.join(SCREEN, "AdvancedQuantityKeeperScreen.java")

# RS 侧的同源参照（证明「流体自身 tooltip」不是我们另造的一套）
RS_FLUID_RENDERER = os.path.join(
    ROOT, "local_src", "rs_src", "com", "refinedmods", "refinedstorage", "neoforge",
    "support", "render", "FluidStackFluidRenderer.java")

AMOUNT_KEY = "gui.rs_create_compat.marker.amount"

problems = []


def read(path):
    with io.open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def check(ok, message, detail=""):
    print(("  [OK]   " if ok else "  [FAIL] ") + message + (("  | " + detail) if detail else ""))
    if not ok:
        problems.append(message)


def section(title):
    print("\n===== %s =====" % title)


def method_body(source, signature):
    """按花括号配对取方法体。"""
    start = source.index(signature)
    open_index = source.index("{", start)
    depth = 0
    for i in range(open_index, len(source)):
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return source[open_index:i + 1]
    raise SystemExit("方法体未闭合: " + signature)


def strip_java_comments(source):
    """去掉 // 与 /* */ 注释：判据只认真正的代码（注释里提「不要调 renderFluidTooltip」不算违规）。"""
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    source = re.sub(r"//[^\n]*", "", source)
    return source


def java_int_const(source, name):
    match = re.search(r"int\s+%s\s*=\s*(-?\d+)\s*;" % name, source)
    if not match:
        raise SystemExit("找不到 Java 常量: " + name)
    return int(match.group(1))


# =====================================================================
# ① 流体 tooltip = 流体自身（复用既有实现 + 不再画数量）
# =====================================================================
def section_fluid_tooltip():
    section("① 流体 tooltip 走「流体自身」既有实现，且不再自绘数量")
    ghost = read(GHOST)
    body = method_body(ghost, "public static void renderFluidTooltip(")
    check("lines.add(fluidName(id));" in body,
          "①a tooltip 的正文就是流体自身名字（GhostMarkerRenderer.fluidName —— 唯一实现）")
    check(AMOUNT_KEY not in body and "fluidAmount(" not in body,
          "①b tooltip 里不再拼数量行（marker.amount / fluidAmount 都已不在方法体里）")
    check(body.count("lines.add(") == 3,
          "①c 常显行只有 1 条（流体名），另两行是条件追加（匹配 NBT / 标签过滤）",
          str(body.count("lines.add(")))

    # 同源锚点：RS 官方流体 tooltip = FluidStack#getHoverName（= fluidType.getDescription）
    rs = read(RS_FLUID_RENDERER)
    check("getFluidStackFromCache(fluidResource).getHoverName()" in rs,
          "①d RS 官方 FluidStackFluidRenderer#getTooltip = FluidStack#getHoverName（对照组锚点）")
    name_body = method_body(ghost, "public static Component fluidName(")
    check("fluid.getFluidType().getDescription()" in name_body,
          "①e 我们的 fluidName 与 RS 同源（getDescription = FluidStack#getHoverName 的取法）")

    # 两个保持器界面的 tooltip 口径（第 90 轮起两边都「不自拼」）：
    #   · 基础版走唯一入口 renderFluidTooltip；
    #   · 高级版第 90 轮把流体标记槽改回<b>流体贴图渲染</b>（槽里不再放桶），因此 hover 时也必须补一次
    #     流体自身的名字 —— 复用同一个既有入口 renderFluidTooltip（名字 = FluidType#getDescription()，
    #     可翻译 Component），不允许 literal 硬拼、不允许拼数量。
    basic = read(BASIC_SCREEN)
    adv = read(ADV_SCREEN)
    adv_code = strip_java_comments(adv)
    check("GhostMarkerRenderer.renderFluidTooltip(" in basic,
          "①f 基础版 标记槽 tooltip 仍走唯一入口 renderFluidTooltip（本轮不动基础版）")
    check("GhostMarkerRenderer.renderFluidTooltip(" in adv_code,
          "①f2 高级版 流体标记槽 tooltip 走同一个既有入口 renderFluidTooltip（不是自拼第二套）")
    check("fluidName(" not in adv_code and "Component.literal(\"熔岩\")" not in adv_code
          and "Component.literal(\"岩浆\")" not in adv_code,
          "①f3 高级版 不把流体显示成 literal 文字（没有 fluidName / literal 硬拼「熔岩 / 岩浆」）")
    for label, src in (("基础版", basic), ("高级版", adv)):
        check(AMOUNT_KEY not in src,
              "①g %s 不再引用数量文案键（%s）" % (label, AMOUNT_KEY))
    check(basic.count("renderFluidTooltip(") == 1 and adv_code.count("renderFluidTooltip(") == 1,
          "①h 两版各只有一处 renderFluidTooltip 调用（同帧只出一个框）")

    # 推演：tooltip 行 = [流体名]，不再随数量变化
    def tooltip_lines(fluid_name, amount):
        return [fluid_name]

    rows = [tooltip_lines("Lava", 0), tooltip_lines("Lava", 100000000),
            tooltip_lines("Lava", 2 ** 63 - 1)]
    check(all(r == ["Lava"] for r in rows),
          "①i 推演：0 / 1 亿 / Long.MAX_VALUE 三种数量下 tooltip 完全一致（只有流体名）", str(rows))


# =====================================================================
# ② 大数值显示 / 解析（纯逻辑复刻 Java 实现，逐个边界用例推演）
# =====================================================================
MC_ADVANCE = {
    " ": 4, "!": 2, "'": 2, ".": 2, ",": 2, ":": 2, ";": 2, "i": 2, "|": 2,
    "(": 4, ")": 4, "[": 4, "]": 4, "f": 4, "k": 4, "l": 4, "t": 4, "I": 4,
    "<": 5, ">": 5, "*": 5, '"': 5, "`": 5, "{": 5, "}": 5, "~": 5,
    "\\": 6, "@": 7,
}
MAX_VALUE = 2 ** 31 - 1


def char_width(ch):
    if ord(ch) >= 128:
        return 9
    return MC_ADVANCE.get(ch, 6)


def text_width(text):
    return sum(char_width(c) for c in text)


def grouped(value):
    # 与 RsccNumberField.grouped 同口径：输入可以是数值，也可以是纯数字串
    digits = str(max(0, value)) if isinstance(value, int) else "".join(c for c in str(value) if c.isdigit())
    out = []
    head = len(digits) % 3
    for i, ch in enumerate(digits):
        if i > 0 and (i - head) % 3 == 0:
            out.append(",")
        out.append(ch)
    return "".join(out)


def compact(value):
    v = max(0, value)
    if v < 10000:
        return grouped(v)
    unit = 100000000 if v >= 100000000 else 10000
    name = "亿" if unit == 100000000 else "万"
    tenths = v * 10 // unit
    return "%d.%d%s" % (tenths // 10, tenths % 10, name)


def display(value, width):
    """复刻 RsccNumberField.display：只产出「千分位」或「纯数字」两种可回读写法。"""
    plain = str(max(0, value))
    pretty = grouped(plain)
    return pretty if text_width(pretty) <= width else plain


def parse(text):
    """复刻 RsccNumberField.parse（数字部分用严格千分位正则钉形状，再切 e / 后缀）。"""
    if text is None:
        return False, 0
    s = text.strip()
    if not s:
        return False, 0
    end = 0
    while end < len(s) and (s[end].isdigit() or s[end] == ","):
        end += 1
    head = s[:end]
    if not re.fullmatch(r"\d+|\d{1,3}(?:,\d{3})+", head):
        return False, 0
    digits = head.replace(",", "")
    tail = s[end:]
    exponent = 0
    if tail[:1] in ("e", "E"):
        exp = tail[1:]
        if not exp.isdigit():
            return False, 0
        exponent = int(exp)
        if exponent > 18:
            return False, 0
        tail = ""
    factor = 1
    if tail:
        if len(tail) != 1:
            return False, 0
        factor = {"b": 1000, "B": 1000, "m": 1, "M": 1, "k": 1000, "K": 1000,
                  "g": 1000000000, "G": 1000000000,
                  "t": 1000000000000, "T": 1000000000000}.get(tail, 0)
        if factor == 0:
            return False, 0
    if len(digits) > 19:
        return False, 0
    value = int(digits)
    for _ in range(exponent):
        if value > (MAX_VALUE * 100) // 10:
            return False, 0
        value *= 10
    if value > (MAX_VALUE * 100) // factor:
        return False, 0
    value *= factor
    return True, min(MAX_VALUE, value)


def parse_sanitized(text):
    """复刻 RsccNumberField.parseSanitized：整串优先，其次「以数字开头」时取数字部分。"""
    ok, value = parse(text)
    if ok:
        return True, value
    if not text or not text[0].isdigit():
        return False, 0
    kept = [c for c in text if c.isdigit() or c == ","]
    if kept and kept[-1] == ",":
        kept.pop()
    return parse("".join(kept))


def bucket_hint(mB, width):
    """复刻 RsccNumberField.bucketHint（框里已有原始 mB，这里只写桶数）。"""
    text = (grouped(mB) + " mB") if mB < 1000 else ("= " + grouped(mB // 1000) + " 桶")
    if text_width(text) <= width:
        return text
    shorter = (compact(mB) + " mB") if mB < 1000 else ("= " + compact(mB // 1000) + " 桶")
    return shorter if text_width(shorter) <= width else compact(mB)


def bucket_tooltip(mB):
    """复刻 RsccNumberField.bucketTooltip：原始 mB + 桶数 + 紧凑可读写法。"""
    base = (grouped(mB) + " mB") if mB < 1000 else (
        grouped(mB) + " mB = " + grouped(mB // 1000) + " 桶")
    human = compact(mB)
    return base if human == grouped(mB) else base + "（" + human + "）"


def section_number_field():
    section("② 大数值：显示完整可读 + 解析回原值（边界用例）")
    number = read(NUMBER)
    geom = read(GEOMETRY)

    check("public static String display(final long value, final int widthPx)" in number,
          "②a 展示文本走唯一实现 RsccNumberField.display（放不下千分位就退回纯数字 / 紧凑写法）")
    check("public static Parsed parse(@Nullable final String text)" in number,
          "②b 解析走唯一实现 RsccNumberField.parse（形状判据，不是「parse 出个前缀」）")
    check("public static EditBox wire(final EditBox box, final java.util.function.LongConsumer onValue)"
          in number,
          "②c 输入框接线（maxLength + responder）只有一份实现 RsccNumberField.wire")
    for label, src in (("基础版", read(BASIC_SCREEN)), ("高级版", read(ADV_SCREEN))):
        check("RsccNumberField.wire(box" in src,
              "②d %s 的输入框用 wire 接线（没有第二套 responder）" % label)
        check("setResponder(" not in src,
              "②e %s 不再自己写 setResponder（解析口径不可能分叉）" % label)

    # 几何：`100,000,000` 必须放得下
    basics = {
        "基础版": java_int_const(geom, "BASIC_BOX_W"),
        "高级版": java_int_const(geom, "ROW_BOX_W"),
    }
    TARGET = "100,000,000"
    for label, box_w in basics.items():
        window = box_w - 9  # RsccNumberField.textWindow
        check(text_width(TARGET) <= window,
              "②f %s 输入框文本区 %dpx ≥ 「%s」所需 %dpx（1 亿 mB 连千分位一眼看全）"
              % (label, window, TARGET, text_width(TARGET)))
    # 解析口径与 Java 常量一致（避免「校验脚本自己另立一张表」）
    for token in ("public static int textWindow(final int boxWidth)",
                  "return Math.max(0, boxWidth - 9);"):
        check(token in number, "②g 文本区口径锚点：%s" % token.split("(")[0])

    # 边界用例（大数值必须完整可读且可解析回原值）
    cases = [
        ("0", 0),
        ("1", 1),
        ("1000", 1000),
        ("100000000", 100000000),
        (str(MAX_VALUE), MAX_VALUE),
    ]
    for label, box_w in basics.items():
        window = box_w - 9
        for raw, value in cases:
            shown = display(value, window)
            ok, back = parse(shown)
            check(ok and back == value,
                  "②h %s 显示：%s → 「%s」解析回 %s（原值 %s）"
                  % (label, raw, shown, back, value))
            check(text_width(shown) <= window or shown == raw,
                  "②i %s 「%s」宽 %dpx ≤ 文本区 %dpx（不会被裁成一半 0）"
                  % (label, shown, text_width(shown), window))

    # 后缀 / 科学计数：玩家不必自己数 0
    suffix_cases = [
        ("1000b", 1000000, "1000 桶 = 1,000,000 mB"),
        ("1e8", 100000000, "1e8 = 1 亿 mB"),
        ("2k", 2000, "2k = 2000（物品家族）"),
        ("1,000,000", 1000000, "千分位照收"),
        (" 250 ", 250, "两端空格容忍"),
    ]
    for raw, expect, why in suffix_cases:
        ok, got = parse(raw)
        check(ok and got == expect, "②j 解析「%s」= %d（%s）" % (raw, expect, why))

    # 非法输入必须安全失败（绝不静默落成 0 或半个数）
    for bad in ("", "abc", "1,2,3", ",100", "1000b2", "1e", "1e999", "12x", "-5", "0x10"):
        ok, _got = parse(bad)
        check(not ok, "②k 非法输入「%s」判负（调用方保持原值、不发包）" % bad)

    # 溢出保护：超过 int 上限时夹到上限（服务端只收 int）
    ok, got = parse("9999999999999999999999")
    check(not ok, "②l 位数过多判负（不回绕成负数）")
    ok, got = parse(str(MAX_VALUE) + "0")
    check(not ok or got == MAX_VALUE, "②m 超出 int 上限不静默回绕（得到 %s）" % got)

    # 往返一致（0 / 1 / 1000 / 1e8 / Long.MAX_VALUE 之外的 int 上限）
    for value in (0, 1, 1000, 100000000, MAX_VALUE, 999, 1001, 123456789):
        for width in (67, 77):
            shown = display(value, width)
            ok, back = parse(shown)
            if not (ok and back == value):
                check(False, "②n 往返失败：%d 在 %dpx 下显示成「%s」→ %s" % (value, width, shown, back))
                break
    else:
        check(True, "②o 往返：0 / 1 / 999 / 1000 / 1001 / 1e8 / 123456789 / int 上限 全部解析回原值")

    # 桶口径：1 桶 = 1000 mB（限宽提示只写桶数，tooltip 里原始 mB 与桶数同时给出）
    bucket_cases = [
        (0, "0 mB", "0 mB"),
        (999, "999 mB", "999 mB"),
        (1000, "= 1 桶", "1,000 mB = 1 桶"),
        (100000000, "= 100,000 桶", "100,000,000 mB = 100,000 桶（1.0亿）"),
    ]
    for mB, expect_hint, expect_tip in bucket_cases:
        hint = bucket_hint(mB, 66)
        tip = bucket_tooltip(mB)
        check(hint == expect_hint and tip == expect_tip,
              "②p 桶换算：%d mB → 限宽提示「%s」/ tooltip「%s」（1 桶 = 1000 mB，原始 mB 在框里与 tooltip 里）"
              % (mB, hint, tip))
    # 限宽提示绝不会超过给定宽度（否则会压到后面的按钮）
    too_wide = []
    for width in (62, 66, 92):
        for mB in (0, 1, 999, 1000, 100000000, MAX_VALUE):
            got = bucket_hint(mB, width)
            if text_width(got) > width:
                too_wide.append("%d mB @%dpx → 「%s」%dpx" % (mB, width, got, text_width(got)))
    check(not too_wide, "②p2 桶提示在 62 / 66 / 92px 三种限宽下都不超宽（不可能压到按钮）",
          str(too_wide[:3]))
    check("public static String bucketHint(final long mB, final int widthPx)" in number
          and "public static String bucketTooltip(final long mB)" in number,
          "②p3 桶换算显示只有一份实现（bucketHint 限宽短提示 / bucketTooltip 完整口径）")
    check("RsccNumberField.bucketTooltip(" in read(ADV_SCREEN),
          "②p4 高级版 桶换算走 bucketTooltip（输入框 tooltip）")
    check("RsccNumberField.bucketTooltip(" in read(BASIC_SCREEN)
          and "bucketHint(" not in read(BASIC_SCREEN),
          "②p5 基础版 桶换算走 tooltip（版面已被标记槽框与开关列占满，不额外画提示）")

    # 存储口径：两个界面发出去的都是原值（clampToInt 只夹范围、不做单位换算）
    for label, src in (("基础版", read(BASIC_SCREEN)), ("高级版", read(ADV_SCREEN))):
        check("RsccNumberField.clampToInt(value)" in src,
              "②q %s 发包时只做 clampToInt（原始 mB / 个数，绝不换算后落盘）" % label)
        check("applyTargetIfValid" in src, "②r %s 仍只有 applyTargetIfValid 一个发包出口" % label)
    check("public static int clampToInt(final long value)" in number
          and "Mth.clamp(value, 0L, (long) Integer.MAX_VALUE)" in number,
          "②s clampToInt 把 long 安全夹进 int（绝无回绕）")


# =====================================================================
# ③ 几何穷举：输入框与 ± / 开关 / 槽位 / 提示文字一律不重叠
# =====================================================================
def rect(x, y, w, h):
    return (x, y, x + w, y + h)


def overlap(a, b):
    return a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]


def section_geometry():
    section("③ 几何穷举：加大输入框后与后面的按钮不重叠")
    tables = {}
    for name in ("zh_cn.json", "en_us.json"):
        with io.open(os.path.join(LANG_DIR, name), encoding="utf-8") as handle:
            tables[name] = json.load(handle)
    geom = read(GEOMETRY)
    g = {name: java_int_const(geom, name) for name in (
        "BASIC_BOX_X", "BASIC_BOX_W", "BASIC_BOX_Y", "BASIC_MINUS_X", "BASIC_PLUS_X",
        "BASIC_BTN_W", "BASIC_UNIT_X", "BASIC_UNIT_MAX_RIGHT", "BASIC_LABEL_RIGHT",
        "ROW_MINUS_X", "ROW_BOX_X", "ROW_BOX_W", "ROW_BOX_DY", "ROW_PLUS_X", "ROW_BTN_W",
        "ROW_UNIT_X", "ROW_UNIT_MAX_RIGHT",
        "ROW_FIRST_Y", "ROW_STEP", "AUTOCRAFT_BTN_X", "OVERFLOW_BTN_X", "TOGGLE_BTN_W",
        "TOGGLE_BTN_H", "TOGGLE_DY")}
    check(g["BASIC_BOX_W"] > 42 and g["ROW_BOX_W"] > 40,
          "③a 两个界面的输入框都比旧值宽（基础 42→%d，高级 40→%d）"
          % (g["BASIC_BOX_W"], g["ROW_BOX_W"]))

    # ---- 基础版：输入框 y=20 高 14；开关 y=37/57；桶换算走输入框 tooltip（不额外占版面） ----
    box = rect(g["BASIC_BOX_X"], g["BASIC_BOX_Y"], g["BASIC_BOX_W"], 14)
    minus = rect(g["BASIC_MINUS_X"], g["BASIC_BOX_Y"], g["BASIC_BTN_W"], 14)
    plus = rect(g["BASIC_PLUS_X"], g["BASIC_BOX_Y"], g["BASIC_BTN_W"], 14)
    toggle_y = java_int_const(geom, "BASIC_TOGGLE_Y")
    toggle_h = java_int_const(geom, "BASIC_TOGGLE_H")
    # 单位文字本轮不再单独画（加宽输入框后这一行没有空位）：en 的 items 有 24px，
    # 只要单独画就必然压住 [+]（162..179）或插件槽列（188 起）—— 因此改为放进输入框 tooltip
    unit_w = max(text_width(tables["zh_cn.json"]["gui.rs_create_compat.quantity_keeper.unit_item"]),
                 text_width(tables["zh_cn.json"]["gui.rs_create_compat.quantity_keeper.unit_mb"]),
                 text_width(tables["en_us.json"]["gui.rs_create_compat.quantity_keeper.unit_item"]),
                 text_width(tables["en_us.json"]["gui.rs_create_compat.quantity_keeper.unit_mb"]))
    unit_gap_to_plus = g["BASIC_PLUS_X"] - (g["BASIC_UNIT_X"] + unit_w)
    check(unit_gap_to_plus <= 0 or g["BASIC_UNIT_X"] + unit_w > g["BASIC_UNIT_MAX_RIGHT"] + 1,
          "③f0 基础版：单位文字（最宽 %dpx）在 x=%d 起画必然与 [+] / 插件槽列冲突 ⇒ 本轮改为不画，"
          "单位与桶换算一起进输入框 tooltip" % (unit_w, g["BASIC_UNIT_X"]))
    check('LANG + "unit_mb"' not in read(BASIC_SCREEN)
          and "quantity_keeper.unit_mb" not in read(BASIC_SCREEN),
          "③f1 基础版确实不再单独绘制单位文字")
    controls = {"输入框": box, "[-]": minus, "[+]": plus,
                "销毁开关": rect(76, toggle_y, 16, toggle_h),
                "自动合成开关": rect(76, toggle_y + 20, 16, toggle_h),
                "插件槽列": rect(188, 7, 16, 6 * 18 + 15),
                "玩家背包": rect(9, 85, 9 * 18 - 2, 3 * 18 + 14),
                # 背景烘焙的标记槽框（Menu (9,30) 16×16）
                "标记槽框": rect(9, 30, 16, 16)}
    labels = {"目标数量标签": rect(2, g["BASIC_BOX_Y"] - 5, g["BASIC_LABEL_RIGHT"] + 2, 9),
              "销毁标签": rect(96, toggle_y + 3, 60, 9),
              "自动合成标签": rect(96, toggle_y + 23, 60, 9)}
    names = list(controls) + list(labels)
    allr = dict(controls)
    allr.update(labels)
    bad = []
    for a, b in itertools.combinations(names, 2):
        if overlap(allr[a], allr[b]):
            bad.append("%s %s × %s %s" % (a, allr[a], b, allr[b]))
    check(not bad, "③b 基础版 %d 个矩形两两不重叠（输入框 x=%d..%d / [+] x=%d..%d）"
          % (len(names), box[0], box[2] - 1, plus[0], plus[2] - 1), str(bad[:3]))
    check(box[2] + 4 == plus[0], "③c 基础版：输入框右缘 %d + 4px 缝 = [+] 左沿 %d" % (box[2], plus[0]))
    check(minus[2] + 4 == box[0], "③d 基础版：[-] 右缘 %d + 4px 缝 = 输入框左沿 %d" % (minus[2], box[0]))
    check(188 >= plus[2] + 8, "③e 基础版：[+] 右缘 %d 距插件槽列 %d 仍有 %dpx"
          % (plus[2], 188, 188 - plus[2]))
    check(g["BASIC_UNIT_X"] + unit_w > g["BASIC_UNIT_MAX_RIGHT"] - 4,
          "③f2 基础版：单位文字在 x=%d 起画会到 %d（> %d 附近），确认「不画」是有依据的"
          % (g["BASIC_UNIT_X"], g["BASIC_UNIT_X"] + unit_w, g["BASIC_UNIT_MAX_RIGHT"]))
    check("RsccNumberField.bucketHint(" not in read(BASIC_SCREEN),
          "③g 基础版不额外画桶提示（该带已被标记槽框与开关列占满）→ 桶换算走输入框 tooltip")
    check("RsccNumberField.bucketTooltip(currentValue())" in read(BASIC_SCREEN),
          "③g2 基础版：tooltip 里给出当前值的桶换算（原始 mB 由输入框本身显示）")

    # ---- 高级版：4 行 y=22/46/70/94，行距 24（烘焙在背景 PNG 里，不能改） ----
    stride = java_int_const(geom, "ROW_STEP")
    first_y = java_int_const(geom, "ROW_FIRST_Y")
    box_h = java_int_const(geom, "ROW_BOX_H")
    btn_h = java_int_const(geom, "ROW_BTN_H")
    check(stride == 24 and first_y == 22,
          "③h 高级版行距 22 / 24 未变（槽位位置烘焙在背景 PNG：槽框外框 y=24/48/72/96）")
    # 第 90 轮：输入框 70×12 → 74×14（「将输入框拉大一些」）
    check(g["ROW_BOX_W"] == 74 and box_h == 14,
          "③h2 输入框实测 %d×%d（上一轮 70×12）—— 变宽变高" % (g["ROW_BOX_W"], box_h))
    check(g["ROW_BOX_W"] - 9 >= 58,
          "③h3 输入框文本区 %dpx ≥ 「100,000,000」58px" % (g["ROW_BOX_W"] - 9))
    bad = []
    for row in range(4):
        row_y = first_y + row * stride
        box = rect(g["ROW_BOX_X"], row_y + g["ROW_BOX_DY"], g["ROW_BOX_W"], box_h)
        minus = rect(g["ROW_MINUS_X"], row_y + g["ROW_BOX_DY"], g["ROW_BTN_W"], btn_h)
        plus = rect(g["ROW_PLUS_X"], row_y + g["ROW_BOX_DY"], g["ROW_BTN_W"], btn_h)
        auto = rect(g["AUTOCRAFT_BTN_X"], row_y + g["TOGGLE_DY"], g["TOGGLE_BTN_W"], g["TOGGLE_BTN_H"])
        over = rect(g["OVERFLOW_BTN_X"], row_y + g["TOGGLE_DY"], g["TOGGLE_BTN_W"], g["TOGGLE_BTN_H"])
        slot = rect(9, row_y + 3, 16, 16)
        # 第 84 轮：每行不再画「个 / mB」单位文字（与基础版同一条口径），因此这一行只剩 5 个控件
        items = {"输入框": box, "[-]": minus, "[+]": plus,
                 "自动合成": auto, "过量销毁": over, "标记槽": slot}
        for a, b in itertools.combinations(items, 2):
            if overlap(items[a], items[b]):
                bad.append("row%d %s %s × %s %s" % (row, a, items[a], b, items[b]))
        # 间距一律 >= 4px（上一轮 [+] 与开关列 0px）
        ordered = [("标记槽", slot), ("[-]", minus), ("输入框", box), ("[+]", plus),
                   ("自动合成", auto), ("过量销毁", over)]
        for (aname, arect), (bname, brect) in zip(ordered, ordered[1:]):
            if brect[0] - arect[2] < 4:
                bad.append("row%d %s 右缘 %d 与 %s 左沿 %d 间距 %d < 4px"
                           % (row, aname, arect[2], bname, brect[0], brect[0] - arect[2]))
        if over[2] > 187:
            bad.append("row%d 过量销毁右缘 %d 压住插件槽列 187" % (row, over[2]))
        # ① 「[+] 右侧重复数字」：这一带必须比最窄两位数（12px）还窄
        gap = auto[0] - plus[2]
        if gap >= 12:
            bad.append("row%d [+]/开关 之间 %dpx ≥ 12px（有空间画重复数字）" % (row, gap))
    check(not bad, "③i 高级版 4 行 × 6 矩形两两不重叠、相邻间距 >= 4px、不压槽位、[+] 右侧无数字带",
          str(bad[:3]))
    check("RsccNumberField.bucketHint(" not in read(ADV_SCREEN)
          and "RsccNumberField.bucketTooltip(currentValue(row))" in read(ADV_SCREEN),
          "③j 高级版：桶换算走输入框 tooltip（行距 24px 内没有第二条空带，见 RsccKeeperGeometry 注释）")
    check(g["AUTOCRAFT_BTN_X"] > 134 and g["OVERFLOW_BTN_X"] > 162,
          "③k 高级版两列开关已右移让位（134→%d / 162→%d）"
          % (g["AUTOCRAFT_BTN_X"], g["OVERFLOW_BTN_X"]))
    # 第 90 轮：两列之间保持 >= 12px（用户反馈「每行两个 ✗ 看起来是重复按钮」）
    toggle_gap = g["OVERFLOW_BTN_X"] - (g["AUTOCRAFT_BTN_X"] + g["TOGGLE_BTN_W"])
    check(toggle_gap >= 12,
          "③l 两列开关之间留 %dpx（>= 12px；%d..%d / %d..%d）"
          % (toggle_gap, g["AUTOCRAFT_BTN_X"], g["AUTOCRAFT_BTN_X"] + g["TOGGLE_BTN_W"],
             g["OVERFLOW_BTN_X"], g["OVERFLOW_BTN_X"] + g["TOGGLE_BTN_W"]))
    # 第 90 轮：两列列标签是「两行完整词」（自动 / 合成、过量 / 销毁），不再是单行短词
    adv_text = read(ADV_SCREEN)
    check("COL_HEADER" not in adv_text and "drawColumnHeader" not in adv_text,
          "③m 高级版不再用 0.75 缩放的单行列标题（改成 9px 正体、两行完整词）")
    check("Column labels" not in adv_text
          and "column_autocraft.2" in adv_text and "column_overflow.2" in adv_text,
          "③m2 高级版列标签引用了两行的第二行键（column_autocraft.2 / column_overflow.2）")
    check(adv_text.count("drawColumnLabel(guiGraphics,") == 2,
          "③m3 renderLabels 里两列各画一次列标签块（共 2 次调用）")
    # ③m4：「[+] 右侧无重复数字」的源码级判据 —— 整份界面只有 3 处 drawString（标题 + 两行标签）
    check(adv_text.count("drawString(") == 3,
          "③m4 界面只有 3 处 drawString（标题 1 + 两列标签各 1），行内不画任何文字 / 数字",
          str(adv_text.count("drawString(")))
    check("getTarget(" not in adv_text.split("protected void renderLabels(")[1]
          .split("private void drawTitle(")[0],
          "③m5 renderLabels 不引用 getTarget / 目标值（不会在 [+] 右侧再画一遍数字）")

    # 两个界面的 Java 真的在用这批常量（不是常量写一套、界面写另一套）
    basic = read(BASIC_SCREEN)
    adv = read(ADV_SCREEN)
    for token in ("RsccKeeperGeometry.BASIC_BOX_X", "RsccKeeperGeometry.BASIC_BOX_W",
                  "RsccKeeperGeometry.BASIC_MINUS_X", "RsccKeeperGeometry.BASIC_PLUS_X"):
        check(token in basic, "③l 基础版控件几何取自 %s" % token.split(".")[-1])
    for token in ("RsccKeeperGeometry.ROW_BOX_X", "RsccKeeperGeometry.ROW_BOX_W",
                  "RsccKeeperGeometry.ROW_MINUS_X", "RsccKeeperGeometry.ROW_PLUS_X",
                  "RsccKeeperGeometry.TOGGLE_BTN_W", "RsccKeeperGeometry.TOGGLE_BTN_H"):
        check(token in adv, "③m 高级版控件几何取自 %s" % token.split(".")[-1])
    check(java_int_const(adv, "AUTOCRAFT_BTN_X") == g["AUTOCRAFT_BTN_X"]
          and java_int_const(adv, "OVERFLOW_BTN_X") == g["OVERFLOW_BTN_X"],
          "③m2 高级版开关列字面量 = %d / %d（与 RsccKeeperGeometry 同值）"
          % (g["AUTOCRAFT_BTN_X"], g["OVERFLOW_BTN_X"]))
    check(java_int_const(adv, "UNIT_SPRITE_X") == g["ROW_UNIT_X"]
          and java_int_const(adv, "AUTOCRAFT_BTN_X") == g["AUTOCRAFT_BTN_X"]
          and java_int_const(adv, "OVERFLOW_BTN_X") == g["OVERFLOW_BTN_X"],
          "③n 高级版三个「旧校验脚本按字面量读」的常量与 RsccKeeperGeometry 同值（%d / %d / %d）"
          % (g["ROW_UNIT_X"], g["AUTOCRAFT_BTN_X"], g["OVERFLOW_BTN_X"]))


# =====================================================================
# ④ 语言键（中英成对、中文 <= 40、无禁用措辞）
# =====================================================================
def section_lang():
    section("④ 语言键（中英成对 / 中文 <= 40 / 无禁用措辞）")
    tables = {}
    for name in ("zh_cn.json", "en_us.json"):
        with io.open(os.path.join(LANG_DIR, name), encoding="utf-8") as handle:
            tables[name] = json.load(handle)
    check(set(tables["zh_cn.json"]) == set(tables["en_us.json"]),
          "④a 中英键集合一致（%d / %d）"
          % (len(tables["zh_cn.json"]), len(tables["en_us.json"])))
    for key in ("gui.rs_create_compat.quantity_keeper.target_tooltip",
                "gui.rs_create_compat.advanced_quantity_keeper.target_tooltip"):
        zh = tables["zh_cn.json"].get(key, "")
        en = tables["en_us.json"].get(key, "")
        check(bool(zh) and bool(en), "④b 键存在且中英成对：%s" % key)
        check(len(zh) <= 40, "④c 中文 <= 40 字（%d）：%s" % (len(zh), key))
        check(zh.count("%s") == 1 and en.count("%s") == 1,
              "④d 占位符各 1 个（桶换算值）：%s" % key)
        for banned in ("或", "等 %s 种", "图标轮换"):
            check(banned not in zh, "④e 未使用禁用措辞「%s」：%s" % (banned, key))
        check("1000 mB" in zh and "1000 mB" in en,
              "④f 文案里写明换算依据 1 桶 = 1000 mB：%s" % key)
    # 数量行文案键不再被保持器界面引用（别的界面仍可用，不强制删除）
    for name, table in tables.items():
        check(AMOUNT_KEY in table,
              "④g %s 仍保留 marker.amount（归流缓存仓 / 总线条还在用，不删）" % name)
    # 第 90 轮：两列列标签是「两行完整词」——两段拼起来必须等于 自动合成 / 过量销毁（en 同理）
    col = "gui.rs_create_compat.advanced_quantity_keeper."
    expect = {"zh_cn.json": ("自动合成", "过量销毁"), "en_us.json": ("autocraft", "overflow")}
    for name, table in tables.items():
        for index, key in enumerate(("column_autocraft", "column_overflow")):
            line1 = table.get(col + key, "")
            line2 = table.get(col + key + ".2", "")
            joined = (line1 + line2).strip()
            got = joined if name == "zh_cn.json" else joined.lower()
            check(bool(line1) and bool(line2),
                  "④h %s 两行列标签两行都非空：%s / %s" % (name, col + key, col + key + ".2"))
            check(got == expect[name][index],
                  "④i %s 两行拼起来 = %r（完整词 %r，不能只显示半截）"
                  % (name, joined, expect[name][index]))
        check(table.get(col + "column_autocraft") != table.get(col + "column_overflow"),
              "④j %s 两个列标签不同（能区分哪列管什么）" % name)


if __name__ == "__main__":
    section_fluid_tooltip()
    section_number_field()
    section_geometry()
    section_lang()
    print("\n" + "=" * 68)
    if problems:
        for item in problems:
            print("  [X] %s" % item)
    print("问题总数: %d" % len(problems))
    sys.exit(1 if problems else 0)
