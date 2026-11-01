#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 109 轮自检：电动飞行背包「精确电量显示 + 低电阈值按剩余可飞分钟」。

用户第 109 条实机反馈（原话要点）：
  > 「这个电动的背包他的定量要能显示准确的数字，比如说两兆之后也能显示准确数字，
  >  不要只显示个 2M；然后它这个电量低究竟在什么时候显示呢？除了我刚刚所说的那个以外，
  >  还需要一个阈值：还能飞行多长时间，单位按分钟算，可以配置，预设还能飞 30 分钟。
  >  怎么算呢？就直接拿当前电量除以那个配置中的耗电量，然后换算成分钟，
  >  再跟人配置的阈值进行对比，小于这个值才提示，否则就不要提示。
  >  我他妈现在充了两兆了一直告诉我电量过低。」

本脚本只读源码 / 语言文件，不改任何 src/main，只用 Python 标准库。

断言清单：
  ① 精确显示：`formatFeExact` 用千分位（`%,d` + Locale.ROOT），旧的 `%.1fM` / `%.1fK`
     紧凑格式**彻底删除**；HUD / tooltip 都用精确值；行为模型 `2,000,000` 逐例核对。
  ② 阈值口径：配置键 `electricJetpackLowPowerMinutes`（默认 30、范围 1..100000），
     旧的 `electricJetpackLowPowerPercent` 在 Config / 背包源码里**完全不存在**；
     阈值 FE = 分钟 × 1200 tick/min × 每 tick 耗电；服务端比较是**严格小于**。
  ③ 用户场景行为模型：默认配置（30 分钟 / 8 FE/tick ⇒ 288,000 FE）下，
     2,000,000 FE（用户「充了两兆」）**不提示**；288,000（正好 30 分钟）不提示；
     287,992（29 分 59 秒）提示；0 提示。剩余分钟数（HUD / tooltip / 副标题共用）逐例核对。
  ④ 提示与显示：低电 title 带「剩余可飞 N 分钟」副标题（原版 subtitle 包）；
     HUD 第三行显示剩余分钟并在低电时变红；tooltip 显示剩余分钟与阈值说明。
  ⑤ 语言键：新增 4 条 zh/en 成对、非空、语种正确。
  6 反例自证：把任一关键判据改回旧写法（2M 缩写 / 百分比阈值 / `<=` / 剩余分钟不带进提示）
     必须判负 —— 反例跑的是**同一套判据函数**，不是另写一份。

用法：python tools/selfcheck_round109_jetpack_fe_display.py
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
except Exception:  # pragma: no cover - 老 Python / 重定向
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java")
PKG = os.path.join(JAVA, "cretae", "cookiewyq", "rs_create_compat")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

ITEM = os.path.join(PKG, "item", "ElectricJetpackItem.java")
HANDLER = os.path.join(PKG, "support", "JetpackFlightHandler.java")
HUD = os.path.join(PKG, "client", "JetpackHudOverlay.java")
CONFIG = os.path.join(PKG, "Config.java")

#: 第 109 轮新增 / 依赖的语言键（zh/en 都必须有）。
NEW_LANG_KEYS = [
    "item.rs_create_compat.electric_jetpack.tooltip.flight_left",
    "item.rs_create_compat.electric_jetpack.tooltip.low_power",
    "item.rs_create_compat.electric_jetpack.hud.flight_left",
    "item.rs_create_compat.electric_jetpack.low_power.subtitle",
]

#: 本轮缺省的配置值（用户口径：预设 30 分钟；第 108 轮已定 8 FE/tick）。
DEFAULT_LOW_POWER_MINUTES = 30
DEFAULT_FLIGHT_COST = 8
TICKS_PER_MINUTE = 20 * 60

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
    """把 // 与 /*...*/ 注释替换成空白（保留换行与字符串字面量）。"""
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
    path = os.path.join(LANG_DIR, name)
    try:
        with io.open(path, encoding="utf-8") as handle:
            return json.load(handle)
    except Exception:
        return {}


# ======================================================================================
# ① 精确显示（千分位，绝不缩写）
# ======================================================================================

def exact_formatter_ok(item_text):
    """`formatFeExact` 必须：`%,d` + Locale.ROOT；且旧的紧凑格式（%.1fM / %.1fK）彻底消失。"""
    code = strip_comments(item_text)
    if "public static String formatFeExact(final int fe)" not in code:
        return False
    if 'String.format(java.util.Locale.ROOT, "%,d"' not in code:
        return False
    for banned in ("%.1fM", "%.1fK", "formatFe(final int fe)"):
        if banned in code:
            return False
    return True


def hud_uses_exact_ok(hud_text):
    """HUD 电量文本两处都走 formatFeExact（当前 / 容量），且没有任何紧凑格式化残留。"""
    code = strip_comments(hud_text)
    if code.count("ElectricJetpackItem.formatFeExact(") < 2:
        return False
    return ("formatFe(" not in code.replace("formatFeExact(", "")
            and "%.1f" not in code
            and "M FE" not in code.replace("formatFeExact", ""))


def tooltip_uses_exact_ok(item_text):
    """tooltip 的电量行也走精确格式（物品 tooltip 是玩家最先看到的数字）。"""
    code = strip_comments(item_text)
    body = method_body(code, r"public void appendHoverText\(")
    if body is None:
        return False
    return ("ElectricJetpackItem.formatFeExact".replace("ElectricJetpackItem.", "") in body
            and 'tooltip.energy' in item_text
            and body.count("formatFeExact(") >= 2)


def format_fe_exact_model(fe):
    """行为模型：与 Java 的 `String.format(Locale.ROOT, "%,d", fe)` 逐例一致（负数按 0）。"""
    return "{:,}".format(max(0, int(fe)))


def format_fe_compact_model(fe):
    """反例模型：旧实现的 %.1fM / %.1fK 缩写（用来证明差异确实存在）。"""
    if fe >= 1_000_000:
        return "%.1fM" % (fe / 1_000_000.0)
    if fe >= 10_000:
        return "%.1fK" % (fe / 1_000.0)
    return str(fe)


# ======================================================================================
# ② 阈值口径：剩余可飞分钟（不再是百分比）
# ======================================================================================

def config_minutes_ok(config_text):
    """配置键必须是 `electricJetpackLowPowerMinutes`（默认 30、范围 1..100000）；
    **旧键 `electricJetpackLowPowerPercent` 必须彻底不存在**（避免两份阈值语义并存）。"""
    code = strip_comments(config_text)
    raw = config_text
    if 'defineInRange("electricJetpackLowPowerMinutes", 30, 1, 100000);' not in code:
        return False
    if "electricJetpackLowPowerPercent" in raw:
        return False
    if "public static int electricJetpackLowPowerMinutes;" not in code:
        return False
    if "electricJetpackLowPowerMinutes = ELECTRIC_JETPACK_LOW_POWER_MINUTES.get();" not in code:
        return False
    return True


def threshold_formula_ok(item_text):
    """阈值 FE = 剩余可飞分钟 × 1200 tick/min × 每 tick 耗电（long 运算，防溢出）。"""
    code = strip_comments(item_text)
    if "public static long lowPowerThresholdFe()" not in code:
        return False
    if "public static final int TICKS_PER_MINUTE = 20 * 60;" not in code:
        return False
    body = method_body(code, r"public static long lowPowerThresholdFe\(\)")
    if body is None:
        return False
    return ("(long) lowPowerMinutes()" in body
            and "(long) TICKS_PER_MINUTE" in body
            and "(long) flightCost()" in body)


def remaining_minutes_ok(item_text):
    """剩余可飞分钟 = 电量 / 耗电 / 1200（向下取整；耗电 ≥1 由 flightCost 保证不会除零）。"""
    code = strip_comments(item_text)
    body = method_body(code, r"public static int remainingFlightMinutes\(final int energy\)")
    if body is None:
        return False
    return ("Math.max(0, energy) / (long) flightCost()" in body
            and "ticks / (long) TICKS_PER_MINUTE" in body)


def handler_minutes_threshold_ok(handler_text):
    """服务端：threshold = lowPowerThresholdFe()；比较必须是**严格小于**（等于阈值不提示）；
    不得再出现百分比阈值算法。"""
    code = strip_comments(handler_text)
    if "final long threshold = ElectricJetpackItem.lowPowerThresholdFe();" not in code:
        return False
    if "if (energyAfter < threshold) {" not in code:
        return False
    if "if (energyAfter <= threshold)" in code:
        return False
    if re.search(r"lowPowerPercent\(\)|/\s*100L?\)\s*;", code):
        return False
    if "electricJetpackLowPowerPercent" in handler_text:
        return False
    return True


# ======================================================================================
# ③ 行为模型：用户「充了两兆」场景 + 边界（与 Java 公式一致）
# ======================================================================================

def threshold_fe(minutes, cost):
    return int(minutes) * TICKS_PER_MINUTE * int(cost)


def remaining_minutes(energy, cost):
    ticks = max(0, int(energy)) // int(cost)
    return ticks // TICKS_PER_MINUTE


def low_power_would_warn(energy, minutes=DEFAULT_LOW_POWER_MINUTES, cost=DEFAULT_FLIGHT_COST):
    """实现口径：`energyAfter < threshold` 才提示（严格小于）。"""
    return int(energy) < threshold_fe(minutes, cost)


def low_power_would_warn_percent(energy, percent=10, capacity=50_000_000):
    """反例模型：旧实现「剩余百分比 ≤ 阈值」（≤ 是旧代码的真实写法）。"""
    return int(energy) <= int(capacity * percent / 100)


def low_power_would_warn_inclusive(energy, minutes=DEFAULT_LOW_POWER_MINUTES, cost=DEFAULT_FLIGHT_COST):
    """反例模型：把「严格小于」写成「小于等于」（等于阈值那一 tick 会误报）。"""
    return int(energy) <= threshold_fe(minutes, cost)


def simulate_minutes_threshold():
    """返回 (场景名, energy, 期望是否提示) —— 全部按用户口径手算。"""
    return [
        ("用户场景：充了两兆 2,000,000 FE / 默认 30 分钟·8 FE/t", 2_000_000, False),
        ("正好 30 分钟（288,000 FE）", 288_000, False),
        ("比 30 分钟少一 tick（287,992 FE）", 287_992, True),
        ("20 分钟（192,000 FE）", 192_000, True),
        ("满电 50,000,000 FE", 50_000_000, False),
        ("空电 0 FE", 0, True),
    ]


# ======================================================================================
# ④ 提示与显示：副标题 / HUD 第三行 / tooltip 阈值说明
# ======================================================================================

def handler_subtitle_ok(handler_text):
    """低电 title 必须带副标题（剩余分钟），且用原版 subtitle 包发送。"""
    code = strip_comments(handler_text)
    return ('LANG_LOW_POWER_SUBTITLE =\n        "item.rs_create_compat.electric_jetpack.low_power.subtitle"'
            in handler_text
            and "ClientboundSetSubtitleTextPacket" in code
            and "sendTitle(player, LANG_LOW_POWER, Component.translatable(LANG_LOW_POWER_SUBTITLE,"
            in code
            and "ElectricJetpackItem.remainingFlightMinutes(energyAfter)" in code)


def hud_flight_left_ok(hud_text):
    """HUD 第三行：剩余可飞分钟（同一函数）+ 低电变红。"""
    code = strip_comments(hud_text)
    return ('LANG_HUD_FLIGHT_LEFT =\n        "item.rs_create_compat.electric_jetpack.hud.flight_left"'
            in hud_text
            and "ElectricJetpackItem.remainingFlightMinutes(snapshot.energy())" in code
            and "Component.translatable(LANG_HUD_FLIGHT_LEFT, flightMinutes)" in code
            and "snapshot.energy() < ElectricJetpackItem.lowPowerThresholdFe()" in code
            and "lowPower ? COLOR_LOW_POWER : COLOR_FLIGHT_LEFT" in code)


def tooltip_threshold_ok(item_text):
    """tooltip 必须写出「剩余可飞分钟」和「低电阈值多少分钟 / 多少 FE」。"""
    code = strip_comments(item_text)
    return ("tooltip.flight_left" in item_text
            and "tooltip.low_power" in item_text
            and "remainingFlightMinutes(energy)" in code
            and "lowPowerMinutes()" in code)


def lang_pairs_ok(zh, en, keys):
    cjk = re.compile(r"[\u3000-\u9fff\uff00-\uffef]")
    for key in keys:
        if key not in zh or key not in en:
            return False
        if not zh[key].strip() or not en[key].strip():
            return False
        if not cjk.search(zh[key]):
            return False
        if cjk.search(en[key]):
            return False
    return True


def lang_placeholders_ok(zh, en, key, expected):
    """占位符数量必须与 Java 传参一致（少一个 %s 会显示裸 %s / 丢数字）。"""
    for table in (zh, en):
        if table.get(key, "").count("%s") != expected:
            return False
    return True


# ======================================================================================
# 主流程
# ======================================================================================

def main():
    item = read(ITEM)
    handler = read(HANDLER)
    hud = read(HUD)
    config = read(CONFIG)
    zh = load_lang("zh_cn.json")
    en = load_lang("en_us.json")

    # ---------------- ① 精确显示 ----------------
    print("=== ① 精确电量显示：千分位、绝不缩写（用户口径「两兆要显示准确数字，不要只显示 2M」） ===")
    check("①-a formatFeExact 用 %,d + Locale.ROOT，旧的 %.1fM / %.1fK 紧凑格式彻底删除",
          exact_formatter_ok(item))
    counterexample("把 formatFeExact 改回 %.1fM 缩写 ⇒ 精确显示判据判负",
                   exact_formatter_ok(item.replace(
                       'String.format(java.util.Locale.ROOT, "%,d", Math.max(0, fe))',
                       'String.format(java.util.Locale.ROOT, "%.1fM", fe / 1_000_000.0D)')) is False)
    check("①-b HUD 的「当前 / 容量」两处都走 formatFeExact（没有任何紧凑格式化残留）",
          hud_uses_exact_ok(hud))
    counterexample("HUD 换回旧的 formatFe(...) 调用 ⇒ 判据判负",
                   hud_uses_exact_ok(hud.replace("ElectricJetpackItem.formatFeExact(",
                                                 "ElectricJetpackItem.formatFe(")) is False)
    check("①-c tooltip 电量行也走精确格式（两处 %s 都是 formatFeExact）", tooltip_uses_exact_ok(item))
    counterexample("tooltip 换回紧凑格式 ⇒ 判据判负",
                   tooltip_uses_exact_ok(item.replace("formatFeExact(", "formatFe(")) is False)
    model = [(2_000_000, "2,000,000"), (50_000_000, "50,000,000"), (0, "0"),
             (999, "999"), (1_234_567, "1,234,567"), (43_217_456, "43,217,456")]
    check("①-d 行为模型：精确格式逐例与 Java 一致（2,000,000 不再是 2.0M）",
          all(format_fe_exact_model(fe) == want for fe, want in model)
          and format_fe_exact_model(2_000_000) != format_fe_compact_model(2_000_000))
    counterexample("模型换成旧的紧凑格式（2,000,000 → 2.0M）⇒ 判据判负",
                   (format_fe_compact_model(2_000_000) == "2,000,000") is False)

    # ---------------- ② 阈值口径 ----------------
    print("=== ② 低电阈值改成「剩余可飞分钟」：配置默认 30、换算 = 分钟×1200×耗电、严格小于 ===")
    check("②-a Config 用 electricJetpackLowPowerMinutes（30 / 1..100000），旧百分比键完全不存在",
          config_minutes_ok(config))
    counterexample("把配置键改回 electricJetpackLowPowerPercent ⇒ 判据判负",
                   config_minutes_ok(config.replace("electricJetpackLowPowerMinutes",
                                                    "electricJetpackLowPowerPercent")) is False)
    counterexample("配置默认值从 30 改成 10 ⇒ 判据判负",
                   config_minutes_ok(config.replace(
                       'defineInRange("electricJetpackLowPowerMinutes", 30,',
                       'defineInRange("electricJetpackLowPowerMinutes", 10,')) is False)
    check("②-b 阈值公式：lowPowerThresholdFe = 分钟 × TICKS_PER_MINUTE(1200) × 耗电（long）",
          threshold_formula_ok(item))
    counterexample("阈值公式漏掉 TICKS_PER_MINUTE（按「分钟×耗电」算，阈值小 1200 倍）⇒ 判据判负",
                   threshold_formula_ok(item.replace("(long) TICKS_PER_MINUTE", "1L")) is False)
    check("②-c 剩余可飞分钟 = 电量/耗电/1200（向下取整，防除零）", remaining_minutes_ok(item))
    counterexample("剩余分钟漏掉 /1200（把 tick 当分钟）⇒ 判据判负",
                   remaining_minutes_ok(item.replace("ticks / (long) TICKS_PER_MINUTE", "ticks")) is False)
    check("②-d 服务端用分钟阈值且比较是严格小于，不再有百分比阈值算法",
          handler_minutes_threshold_ok(handler))
    counterexample("把 < 改成 <=（正好 30 分钟也提示）⇒ 判据判负",
                   handler_minutes_threshold_ok(handler.replace(
                       "if (energyAfter < threshold) {", "if (energyAfter <= threshold) {")) is False)

    # ---------------- ③ 用户场景行为模型 ----------------
    print("=== ③ 用户场景：充了两兆（2,000,000 FE）默认配置下不该再提示「电量不足」 ===")
    scenarios = simulate_minutes_threshold()
    details = []
    ok_all = True
    for name, energy, want in scenarios:
        got = low_power_would_warn(energy)
        details.append("%s ⇒ %s（期望 %s）" % (name, "提示" if got else "不提示",
                                               "提示" if want else "不提示"))
        ok_all = ok_all and got == want
    check("③-a 分钟阈值六场景全对（2M 不提示；288,000 不提示；287,992 / 192,000 / 0 提示；满电不提示）",
          ok_all)
    for line in details:
        print("       " + line)
    check("③-b 阈值换算：30 分钟 × 1200 × 8 = 288,000 FE（用户口径手算值）",
          threshold_fe(30, 8) == 288_000)
    check("③-c 剩余分钟：2,000,000 FE ⇒ 208 分钟（即用户那台「充了两兆」能飞 3 小时 28 分）",
          remaining_minutes(2_000_000, 8) == 208)
    check("③-d 剩余分钟边界：288,000 ⇒ 30；287,992 ⇒ 29；0 ⇒ 0；满电 50,000,000 ⇒ 5208",
          remaining_minutes(288_000, 8) == 30
          and remaining_minutes(287_992, 8) == 29
          and remaining_minutes(0, 8) == 0
          and remaining_minutes(50_000_000, 8) == 5208)
    check("③-e 配置可改：10 分钟 / 20 FE/t ⇒ 阈值 240,000 FE（公式随配置走，不是写死 30/8）",
          threshold_fe(10, 20) == 240_000
          and low_power_would_warn(239_999, 10, 20)
          and not low_power_would_warn(240_000, 10, 20))
    counterexample("模型退回旧百分比阈值（10% = 500 万）⇒ 用户 2M 场景被误报，判据判负",
                   (low_power_would_warn_percent(2_000_000) == low_power_would_warn(2_000_000)) is False)
    counterexample("模型把严格小于写成「小于等于」（288,000 正好 30 分钟也提示）⇒ 判据判负",
                   (low_power_would_warn_inclusive(288_000) == low_power_would_warn(288_000)) is False)

    # ---------------- ④ 提示 / HUD / tooltip ----------------
    print("=== ④ 提示与显示：低电副标题写剩余分钟；HUD 第三行；tooltip 写阈值 ===")
    check("④-a 低电 title 带「剩余可飞 N 分钟」副标题（原版 subtitle 包 + 同一剩余分钟函数）",
          handler_subtitle_ok(handler))
    counterexample("低电 title 退回「只发标题、不带分钟」⇒ 判据判负",
                   handler_subtitle_ok(handler.replace(
                       "sendTitle(player, LANG_LOW_POWER, Component.translatable(LANG_LOW_POWER_SUBTITLE,",
                       "sendTitle(player, LANG_LOW_POWER); // Component.translatable(LANG_LOW_POWER_SUBTITLE,")) is False)
    check("④-b HUD 第三行显示剩余可飞分钟，并在低于阈值时变色（与低电提示同一口径）",
          hud_flight_left_ok(hud))
    counterexample("HUD 不再显示剩余分钟 ⇒ 判据判负",
                   hud_flight_left_ok(hud.replace(
                       "Component.translatable(LANG_HUD_FLIGHT_LEFT, flightMinutes)", "Component.empty()")) is False)
    check("④-c tooltip 写出剩余可飞分钟 + 低电阈值说明（多少分钟 / 多少 FE）",
          tooltip_threshold_ok(item))
    counterexample("tooltip 删掉阈值说明 ⇒ 判据判负",
                   tooltip_threshold_ok(item.replace("tooltip.low_power", "tooltip.charge")) is False)

    # ---------------- ⑤ 语言键 ----------------
    print("=== ⑤ 语言键：新增 4 条 zh/en 成对、占位符数量正确 ===")
    check("⑤-a 新增 4 条语言键 zh/en 成对、非空、语种正确", lang_pairs_ok(zh, en, NEW_LANG_KEYS))
    missing = [key for key in NEW_LANG_KEYS if key not in zh or key not in en]
    if missing:
        print("       缺失: " + ", ".join(missing))
    counterexample("把 en 的一条改成中文 ⇒ 语言成对判据判负",
                   lang_pairs_ok(zh, dict(en, **{NEW_LANG_KEYS[0]: "剩余可飞约 %s 分钟"}),
                                 [NEW_LANG_KEYS[0]]) is False)
    check("⑤-b 占位符：hud.flight_left / tooltip.flight_left / low_power.subtitle 各 1 个 %s，"
          "tooltip.low_power 2 个 %s",
          lang_placeholders_ok(zh, en, NEW_LANG_KEYS[0], 1)
          and lang_placeholders_ok(zh, en, NEW_LANG_KEYS[1], 2)
          and lang_placeholders_ok(zh, en, NEW_LANG_KEYS[2], 1)
          and lang_placeholders_ok(zh, en, NEW_LANG_KEYS[3], 1))
    counterexample("把 tooltip.low_power 少写一个 %s（阈值 FE 显示不出来）⇒ 判据判负",
                   lang_placeholders_ok(zh, en, NEW_LANG_KEYS[1], 1) is False)

    # ---------------- 汇总 ----------------
    print("")
    print("断言总数 %d（含反例自证 %d 条）" % (CHECKS[0], COUNTER[0]))
    if FAILS:
        print("问题总数: %d" % len(FAILS))
        for item_name in FAILS:
            print(" - " + item_name)
        return 1
    print("SELFCHECK OK (%d checks, %d counterexamples)" % (CHECKS[0], COUNTER[0]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
