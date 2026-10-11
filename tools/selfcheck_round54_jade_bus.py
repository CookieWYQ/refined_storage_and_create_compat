#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 54 轮：**Jade 总线提示**（用户第 8 项 / 任务 A）的自检。

被检查的交付：
    * 给输入总线 / 输出总线提供瞄准提示：与哪台执行仓绑定、按
      「原料 / 输入时原料 / 流体 / 成品 / 废料 / 中间产物」六节列出它此刻负责什么、
      输入总线为全自动时额外强调；
    * 新增配置项控制显示方式：默认**一直显示**，可切成「按住 Shift 才显示」；
    * 未安装 Jade 一定不崩（可选依赖）。

断言（任一 FAIL 即退出码 1）：
    ① **按 Jade 官方 API 注册**：插件类带 `@WailaPlugin` 且实现 `IWailaPlugin`，
       同时实现 `IWailaCommonRegistration`（服务端数据）与 `IWailaClientRegistration`
       （客户端文案）两个入口；用到的每个 Jade API 类型都<b>真实存在于本地制品</b>
       `libs/jade-*.jar` 里（不猜 API）；
    ② 覆盖**输入与输出两条总线**（公共注册与客户端注册各两条，类名 = RS 的两个方块类）；
    ③ 数据只读来源正确：服务端 provider 从方块实体读，客户端只读 `getServerData()`
       （不在客户端碰方块实体，也不新增同步包）；
    ④ **自动模式有强调**：AUTO 位取自 `rscc$isAutoCollect()`，且文案键中英都在；
    ⑤ **分类按「原料 / 输入时原料 / …」六节**：六节顺序与「类别详细配置」界面同源，
       物品输入按 `isStartIngredient`（本仓起步原料表）分流，六节名复用既有键（不新造第二套）；
    ⑥ **配置项默认值 = 一直显示**，且「按 Shift 显示」才走 Shift 门控；
    ⑦ **缺失 Jade 时不崩**：三个新类只被彼此引用，公共注册路径与其闭包内无客户端类型；
    ⑧ **提示不溢出**：每组最多列 N 件代表物并显式截断（宽度上限锚点），不枚举种类数。

用法：python tools/selfcheck_round54_jade_bus.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import glob
import io
import json
import os
import re
import sys
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_ROOT = os.path.join(ROOT, "src", "main", "java")
PKG = "cretae.cookiewyq.rs_create_compat"
PKG_PATH = PKG.replace(".", "/")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

PLUGIN = PKG_PATH + "/client/jade/RsccJadePlugin.java"
CLIENT_PROVIDER = PKG_PATH + "/client/jade/RsccBusJadeProvider.java"
SERVER_DATA = PKG_PATH + "/compat/jade/RsccBusJadeServerData.java"
PAYLOAD = PKG_PATH + "/compat/jade/RsccBusJadePayload.java"
CONFIG = PKG_PATH + "/Config.java"

# 专服上根本不存在的类型（与 selfcheck_round47 同一批前缀）
CLIENT_PREFIXES = (
    "net.minecraft.client.",
    "com.mojang.blaze3d.",
    "org.lwjgl.",
    "net.neoforged.neoforge.client.",
)

# 本插件用到的 Jade API 类型（必须真实存在于 libs 里的 Jade 制品）
JADE_API_TYPES = (
    "snownee/jade/api/WailaPlugin.class",
    "snownee/jade/api/IWailaPlugin.class",
    "snownee/jade/api/IWailaCommonRegistration.class",
    "snownee/jade/api/IWailaClientRegistration.class",
    "snownee/jade/api/IServerDataProvider.class",
    "snownee/jade/api/IBlockComponentProvider.class",
    "snownee/jade/api/BlockAccessor.class",
    "snownee/jade/api/ITooltip.class",
    "snownee/jade/api/config/IPluginConfig.class",
)

FAILURES = []
CHECKS = [0]


def check(name, ok, detail="", hard=True):
    CHECKS[0] += 1
    if not ok and hard:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else ("FAIL" if hard else "INFO"), name,
                       (" | " + detail) if (detail and not ok) else ""))


def strip_comments(text):
    """只去掉注释（保留字符串字面量）：用于「必须看到字面量」的断言（键名 / 配置值 / 拼接写法）。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif c == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join("\n" if ch == "\n" else " " for ch in text[i:j]))
            i = j
        elif c == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"' or text[j] == "\n":
                    j += 1
                    break
                j += 1
            out.append(text[i:j])
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def strip_comments_and_strings(text):
    """把注释与字符串字面量换成空格（保守实现：足够本项目使用，避免文档里的类名误报）。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif c == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join("\n" if ch == "\n" else " " for ch in text[i:j]))
            i = j
        elif c == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"' or text[j] == "\n":
                    j += 1
                    break
                j += 1
            out.append("".join("\n" if ch == "\n" else " " for ch in text[i:j]))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


class Source:
    def __init__(self, rel, text):
        self.rel = rel
        self.text = text
        self.code = strip_comments_and_strings(text)
        self.raw_code = strip_comments(text)


def load_sources():
    sources = {}
    for dirpath, _dirs, files in os.walk(JAVA_ROOT):
        for name in files:
            if not name.endswith(".java"):
                continue
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, JAVA_ROOT).replace(os.sep, "/")
            with io.open(full, encoding="utf-8") as handle:
                sources[rel] = Source(rel, handle.read())
    return sources


def method_body(code, signature_re):
    m = re.search(signature_re, code)
    if not m:
        return None
    brace = code.find("{", m.end() - 1)
    if brace < 0:
        return None
    depth = 0
    i = brace
    while i < len(code):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[m.start():i + 1]
        i += 1
    return None


def load_lang(name):
    with io.open(os.path.join(LANG_DIR, name), encoding="utf-8") as handle:
        return json.load(handle)


def has_client_prefix(text):
    return [p for p in CLIENT_PREFIXES if p in text]


def main():
    sources = load_sources()
    missing_files = [rel for rel in (PLUGIN, CLIENT_PROVIDER, SERVER_DATA, PAYLOAD)
                     if rel not in sources]
    check("① 四个新文件都在（插件 / 客户端 provider / 服务端数据 / 公共载荷）",
          not missing_files, "; ".join(missing_files))
    if missing_files:
        return report()

    plugin = sources[PLUGIN]
    client_provider = sources[CLIENT_PROVIDER]
    server_data = sources[SERVER_DATA]
    payload = sources[PAYLOAD]
    config = sources.get(CONFIG)

    zh = load_lang("zh_cn.json")
    en = load_lang("en_us.json")

    # ---------------- ① 按官方 API 注册 ----------------
    jars = sorted(glob.glob(os.path.join(ROOT, "libs", "jade-*.jar")))
    check("①a 找到工程内 Jade 制品（libs/jade-*.jar）", bool(jars),
          "libs 下没有 jade 制品，无法核对 API")
    if jars:
        with zipfile.ZipFile(jars[0]) as jar:
            entries = set(jar.namelist())
        absent = [t for t in JADE_API_TYPES if t not in entries]
        check("①b 用到的 Jade API 类型真实存在于 %s（%d 个）" % (os.path.basename(jars[0]),
                                                                len(JADE_API_TYPES)),
              not absent, "; ".join(absent))
    check("①c @WailaPlugin + implements IWailaPlugin",
          "@WailaPlugin" in plugin.code and re.search(r"implements\s+IWailaPlugin", plugin.code) is not None)
    common_body = method_body(plugin.code, r"public\s+void\s+register\s*\(\s*(?:final\s+)?IWailaCommonRegistration")
    client_body = method_body(plugin.code, r"public\s+void\s+registerClient\s*\(\s*(?:final\s+)?IWailaClientRegistration")
    check("①d 公共注册入口 register(IWailaCommonRegistration) 存在", common_body is not None)
    check("①e 客户端注册入口 registerClient(IWailaClientRegistration) 存在", client_body is not None)

    # ---------------- ② 覆盖输入 / 输出两条总线 ----------------
    for label, body in (("公共注册", common_body), ("客户端注册", client_body)):
        if body is None:
            check("② %s 覆盖输入总线 + 输出总线" % label, False, "方法体缺失")
            continue
        importer = re.search(r"ImporterBlock\.class", body) is not None
        exporter = re.search(r"ExporterBlock\.class", body) is not None
        check("② %s 覆盖输入总线 + 输出总线（%s / %s）"
              % (label, "ImporterBlock" if importer else "缺 ImporterBlock",
                 "ExporterBlock" if exporter else "缺 ExporterBlock"),
              importer and exporter)
    if common_body is not None:
        check("②c 公共注册用官方 API registerBlockDataProvider（两条）",
              len(re.findall(r"registerBlockDataProvider\s*\(", common_body)) == 2,
              "出现 %d 次" % len(re.findall(r"registerBlockDataProvider\s*\(", common_body)))
        check("②d 公共注册体不含任何客户端类型",
              not has_client_prefix(common_body), "; ".join(has_client_prefix(common_body)))
    if client_body is not None:
        check("②e 客户端注册用官方 API registerBlockComponent（输入 / 输出各一条）",
              len(re.findall(r"registerBlockComponent\s*\(", client_body)) == 3,
              "出现 %d 次（伪装 1 + 总线 2）" % len(re.findall(r"registerBlockComponent\s*\(", client_body)))

    # ---------------- ③ 数据来源（服务端权威 → Jade 通道，不新增同步包） ----------------
    check("③a 服务端数据 provider 实现 IServerDataProvider<BlockAccessor>",
          re.search(r"implements\s+IServerDataProvider\s*<\s*BlockAccessor\s*>", server_data.code) is not None)
    check("③b 服务端从方块实体取数据（appendServerData → 公共载荷 write）",
          re.search(r"appendServerData\s*\(", server_data.code) is not None
          and re.search(r"RsccBusJadePayload\.write\s*\(", server_data.code) is not None)
    check("③c 客户端只读 getServerData()（不碰方块实体）",
          re.search(r"getServerData\s*\(\s*\)", client_provider.code) is not None
          and "getBlockEntity" not in client_provider.code)
    check("③d 公共载荷类不引用任何 Jade 类型（Jade 缺席也能加载）",
          "snownee" not in payload.code)
    check("③e 未新增自定义同步包（network 包里没有 Jade 相关包类）",
          not [rel for rel in sources
               if rel.startswith(PKG_PATH + "/network/") and "Jade" in sources[rel].code])

    # ---------------- ④ 自动模式强调 ----------------
    check("④a AUTO 位取自输入总线的 rscc$isAutoCollect()",
          re.search(r"root\.putBoolean\(\s*AUTO\s*,\s*importer\.rscc\$isAutoCollect\(\)\s*\)",
                    payload.code) is not None)
    check("④b 自动模式单独成行（LANG + \"auto\"）",
          re.search(r'LANG\s*\+\s*"auto"', payload.raw_code) is not None)
    for key in ("gui.rs_create_compat.jade_bus.auto",):
        check("④c 文案键 %s 中英都在" % key, key in zh and key in en)

    # ---------------- ⑤ 六节分类 ----------------
    order = re.search(r"GROUP_ORDER\s*=\s*List\.of\((.*?)\);", payload.code, re.S)
    if order is None:
        check("⑤a 六节顺序常量 GROUP_ORDER 存在", False)
    else:
        names = re.findall(r"GROUP_([A-Z]+)", order.group(1))
        expected = ["MATERIALS", "FEEDSTOCK", "FLUIDS", "PRODUCTS", "SCRAP", "INTERMEDIATES"]
        check("⑤a 六节顺序 = %s" % " → ".join(expected), names == expected, str(names))
    check("⑤b 物品输入按「本仓起步原料表」分流（isStartIngredient）",
          re.search(r"owner\.isStartIngredient\s*\(", payload.code) is not None)
    check("⑤c 六节名复用「类别详细配置」界面的键（不新造第二套）",
          re.search(r'GROUP_LANG\s*=\s*"gui\.rs_create_compat\.bus_config\.group\."', payload.raw_code) is not None)
    check("⑤d 语言文件里没有新的 jade_bus.group.* 键（六节名只有一套）",
          not [k for k in zh if k.startswith("gui.rs_create_compat.jade_bus.group")])
    for section in ("materials", "feedstock", "fluids", "products", "scrap", "intermediates"):
        key = "gui.rs_create_compat.bus_config.group." + section
        check("⑤e 分节键 %s 中英都在" % key, key in zh and key in en)

    # ---------------- ⑥ 配置项默认值 = 一直显示 ----------------
    if config is None:
        check("⑥ Config.java 存在", False, CONFIG)
    else:
        check("⑥a jadeBusTooltipMode 默认 always（= 一直显示）",
              re.search(r'define\(\s*"jadeBusTooltipMode"\s*,\s*"always"\s*\)', config.raw_code) is not None)
        check("⑥b 静态开关默认 false（false = 一直显示）",
              re.search(r"jadeBusTooltipShiftOnly\s*=\s*false", config.code) is not None)
        check("⑥c 只有显式 shift 才开启 Shift 门控（未知值回落 always）",
              re.search(r'"shift"\.equalsIgnoreCase\(\s*jadeBusMode\s*\)', config.raw_code) is not None
              and re.search(r'"always"\.equalsIgnoreCase\(\s*jadeBusMode\s*\)', config.raw_code) is not None)
        check("⑥d 配置项注释写清取值与生效时机",
              "按 Shift" in config.text and "一直显示" in config.text)
    gate = re.search(r"Config\.jadeBusTooltipShiftOnly\s*&&\s*!\s*RsccTooltipLayers\.held", client_provider.code)
    check("⑥e 客户端 provider 用配置 + 工程唯一的 Shift 判据做门控", gate is not None)
    check("⑥f 「按 Shift 显示」档位下有提示行（LANG + \"hold\"），不会静默",
          re.search(r'LANG\s*\+\s*"hold"', client_provider.raw_code) is not None
          and "gui.rs_create_compat.jade_bus.hold" in zh)

    # ---------------- ⑦ 缺失 Jade 时不崩 ----------------
    forbidden_refs = {}
    for rel, src in sources.items():
        for name in ("RsccBusJadeServerData", "RsccBusJadePayload", "RsccBusJadeProvider", "RsccJadePlugin"):
            if name in src.code and rel not in (PLUGIN, CLIENT_PROVIDER, SERVER_DATA, PAYLOAD):
                forbidden_refs.setdefault(name, []).append(rel)
    check("⑦a 四个新类只被彼此引用（Jade 缺席时没有任何第三方加载它们）",
          not forbidden_refs,
          "; ".join("%s <- %s" % (k, v) for k, v in sorted(forbidden_refs.items())))
    for rel in (SERVER_DATA, PAYLOAD):
        hits = has_client_prefix(sources[rel].code)
        check("⑦b %s 不引用客户端类型" % os.path.basename(rel), not hits, "; ".join(hits))
        check("⑦c %s 不 import 本模组 client 包" % os.path.basename(rel),
              not re.search(r"import\s+%s\.client\." % re.escape(PKG), sources[rel].code))
    check("⑦d 公共载荷类的入口带服务端 / 客户端守卫（客户端调用不写数据）",
          re.search(r"level\.isClientSide\(\)", payload.code) is not None)
    check("⑦e 客户端 provider 用 getUid 暴露唯一 id（Jade 配置项键的前提）",
          re.search(r"getUid\s*\(\s*\)", client_provider.code) is not None
          and "fromNamespaceAndPath" in client_provider.code)

    # ---------------- ⑦f Jade 配置翻译键（缺失会抛 AssertionError 并丢资源包） ----------------
    for key in ("config.jade.plugin_rs_create_compat.bus",
                "config.jade.plugin_rs_create_compat.bus_data"):
        check("⑦f Jade 配置翻译键 %s 中英都在" % key, key in zh and key in en)

    # ---------------- ⑧ 溢出上限 ----------------
    cap = re.search(r"MAX_NAMES_PER_GROUP\s*=\s*(\d+)", payload.code)
    check("⑧a 每组代表物有明确上限且 ≤ 3", cap is not None and int(cap.group(1)) <= 3,
          cap.group(1) if cap else "缺常量")
    check("⑧b 上限真的用在渲染里（shown < MAX_NAMES_PER_GROUP）",
          re.search(r"shown\s*<\s*MAX_NAMES_PER_GROUP", payload.code) is not None)
    check("⑧c 截断只写「…」，不写数量 / 不枚举种类数",
          "· …" in payload.raw_code and not re.search(r"等\s*\d+\s*种", payload.raw_code))
    check("⑧d 提示只由公共载荷拼（客户端 provider 不自己拼文案）",
          re.search(r"RsccBusJadePayload\.lines\s*\(", client_provider.code) is not None
          and not re.search(r"Component\.translatable\(\s*\"gui\.rs_create_compat\.jade_bus", client_provider.code))
    banned = ("或", "等 N 种", "图标轮换")
    new_keys = [k for k in zh if k.startswith("gui.rs_create_compat.jade_bus.")
                or k.startswith("config.jade.plugin_rs_create_compat.bus")]
    offenders = []
    for key in new_keys:
        for word in banned:
            if word in str(zh[key]) or word in str(en[key]):
                offenders.append("%s:%s" % (key, word))
    check("⑧e 新增文案不含「或 / 等 N 种 / 图标轮换」（%d 条）" % len(new_keys),
          not offenders, "; ".join(offenders))

    return report()


def report():
    print("")
    if FAILURES:
        print("[FAIL] 共 %d 项断言失败（检查 %d 项）：" % (len(FAILURES), CHECKS[0]))
        for item in FAILURES:
            print("   - %s" % item)
        return 1
    print("[OK] Jade 总线提示：%d 项断言全部通过" % CHECKS[0])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
