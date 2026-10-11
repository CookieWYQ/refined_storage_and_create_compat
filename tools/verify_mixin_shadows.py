#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Mixin @Shadow / 注入目标 静态校验器（独立运行，不依赖启动游戏）

背景
----
Mixin 的 `@Shadow` 是按 **字段/方法描述符**（泛型擦除后的 JVM 描述符）精确匹配的，
一旦写错（最常见：把 `T extends NetworkNode` 擦除后的上界接口写成某个子类），
会在**模组加载期**抛 `InvalidMixinException: @Shadow field xxx was not located in the
target class ...` 直接崩游戏。`@Inject/@Redirect/@Overwrite` 的目标方法签名写错同理。

本脚本做的事
------------
1. 扫描 `src/main/java/cretae/cookiewyq/rs_create_compat/mixin/**` 下所有 Mixin 源文件；
2. 解析每个 `@Mixin(Target.class)` 的目标类，以及类内所有 `@Shadow` 字段 / 方法与
   `@Final` / `@Mutable` 标注；
3. 用 `javap -p -s` 从**运行时真实 jar** 读出目标类及其父类/接口链的真实成员描述符：
   - Minecraft 类 → neoformruntime 的 `compiledWithNeoForge_*_output.jar`
   - Refined Storage 类 → gradle 缓存的 `refinedstorage-neoforge-<pin 版本>.jar`
     （pin 版本取自 gradle.properties 的 refinedstorage_version，见下面「缓存判据」）
4. 逐个 `@Shadow` 做「名字 + 描述符」精确匹配，输出 `[OK] / [缺失] / [描述符不符]`；
   描述符不符时同时打印「代码写的类型」与「真实描述符（含声明类）」；
5. 顺带把每个 `@Inject / @Redirect / @Overwrite / @Accessor / @Invoker` 的目标成员
   也做同样校验（名字唯一性 / 描述符精确匹配）。
6. 【继承成员风险】每个 `@Shadow` 字段 / 方法，若命中的成员**不是声明在目标类自身**，而是声明在
   父类 / 接口上，输出 `[风险]` 并计入问题总数（退出码 1）。因为 Mixin 的 `@Shadow` 可能无法定位
   「继承自父类」的成员，会在**模组加载期（或客户端打开界面时）**抛
   `InvalidMixinException: @Shadow field xxx was not located in the target class` 直接崩游戏
   （2026-09-13 09:37 崩溃报告：mainNetworkNode 声明在泛型祖父类
   `AbstractNetworkNodeContainerBlockEntity` 上，子类 shadow 必然失败）。
   修法：在「**声明类**」上挂 `@Accessor`，或改用公开 API / 公开 getter。
   `@Accessor / @Invoker` 的目标成员同理，必须存在于其 `@Mixin` 的类自身。
   极少数经实测确认可用的继承 shadow 可显式登记进 `KNOWN_OK_INHERITED_SHADOWS` 白名单豁免。
7. 【注入目标风险 —— 2026-09-13 客户端崩溃的根因】每个 `@Inject / @Redirect / @Overwrite` 的
   **目标方法**，若其**声明类 ≠ 该 Mixin 的 `@Mixin` 目标类**（即来自父类 / 接口），输出 `[风险]`
   并计入问题总数（退出码 1）。原因：**Mixin 只能改写「目标类自身字节码里存在的方法」**，
   目标类没覆写的父类方法在目标类里没有任何方法体可供注入，运行时会抛
   `InvalidInjectionException: Critical injection failure: @Inject annotation on xxx could not
   find any targets matching 'yyy'`（实证：`ExporterScreen` 自身不声明 `mouseClicked`，
   它声明在父类 `AbstractBaseScreen` 上，于是客户端一打开输出总线界面就崩）。
   修法：改为注入目标类自身声明的方法（如 `init`），或用自绘控件 / 事件等机制替代。
8. 【@Redirect 工厂 handler 签名风险 —— 2026-10-11 专用服务端「启动即崩」的根因】此前本校验器
   **只看被注入的目标方法是否存在**，完全不看 `@Redirect` **处理函数（handler）自己的签名**，
   于是出现「报 0 问题、实机却崩」：1.1.0 把 `@At(value = "NEW", target = "...PatternSlot;")`
   改了，却忘了把 handler 的返回类型从 `net.minecraft.world.inventory.Slot` 改成 `PatternSlot`，
   Mixin 在**模组加载期**抛（实机日志原文）：
   `InvalidInjectionException: @Redirect factory method ...::rscc$patternOnlyManagerSlot has an
    invalid signature. Found unexpected return type net.minecraft.world.inventory.Slot, expected
    com.refinedmods.refinedstorage.common.autocrafting.PatternSlot.`
   现在对每个 `@Redirect` 追加三条静态规则（规则取自 Mixin 0.8.7 `@Redirect` 的官方 javadoc，
   即 net.fabricmc:sponge-mixin 的 Redirect.html，本工程 gradle 缓存内可查）：
     a. **返回类型**：NEW（工厂模式）→ 必须**精确等于**被构造的类型；INVOKE → 等于被调用方法的
        返回类型；FIELD/GETFIELD/GETSTATIC → 等于字段类型；PUTFIELD/PUTSTATIC → `void`；
        INSTANCEOF → `boolean` 或 `Class`。
     b. **形参**：handler 形参 = 「@At 目标调用的实参」+「（可选）目标方法自身形参的前缀」。
        NEW 的「目标调用实参」= 被构造构造器的形参；INVOKE 的非静态方法额外前置 owner 实例。
        为免误报：形参上出现 MixinExtras 的 `@Local/@Share/@Definition` 时只校验前缀部分；
        出现 `@Coerce` 时整条形参规则跳过（返回类型仍然严格校验）。
     c. **@At.target 与真实字节码一致**：NEW 的 owner/构造器、INVOKE 的 owner+名字+描述符、
        FIELD 的 owner+字段名+类型，都必须能在真实 jar 里找到。
   反例自证（不改工程文件，直接复现崩溃那一对签名）：
       python tools/selfcheck_round64_redirect_factory_signature.py

【2026-10-11 新增：客户端一遍 / 专用服务端（DEDICATED_SERVER）一遍】
=================================================================
为什么必须再加一遍
------------------
1.1.0 让专用服务端「启动即崩」，而**当时所有编译期 / 客户端语境的校验都报 0 问题**。
原因是编译期看的是 `compiledWithNeoForge_*_output.jar` —— 那是 **client+server 合并**产物，
`net.minecraft.client.**` 在里面**存在**，于是：
  * 「专服上根本不存在的类」照样解析成功 ⇒ dist 违规全部放行；
  * `rs_create_compat.mixins.json` 的 common（非 client）段里的 Mixin 会不会在专服上
    引用客户端专属类，此前没有任何断言。
（`@Redirect` 工厂 handler 签名那条已经由上一节补上；本节补的是 **dist 这一遍**。）

两遍各自独立报问题数
--------------------
  * **第 1 遍 CLIENT**：沿用原行为（合并 jar，不过滤 dist），并额外把「引用了客户端专属类」
    记为 INFO（客户端上有这些类是正常的）。
  * **第 2 遍 DEDICATED_SERVER**：
      ① **dist 视界**：`net.minecraft.client.**` / `com.mojang.blaze3d.**` /
         `net.neoforged.neoforge.client.**` / `org.lwjgl.**` 一律视为**不存在**
         （这就是 NeoForge 运行期 dist 清理的语义：真实类
         `loader-4.0.43.jar!/net/neoforged/fml/common/asm/RuntimeDistCleaner.class`
         的常量池里就是 "Attempted to load class {} for invalid dist {}"；
         实证崩溃：用户专服日志里的
         `Attempted to load class ... for invalid dist DEDICATED_SERVER` /
         `NoClassDefFoundError: net.minecraft.client.gui.components.toasts.Toast`）。
         ⇒ 任何 `@Shadow` / 注入目标 / `@At.target` 解析到这个视界里的类，都会被判为
           「专服上不存在」而不是「OK」。
      ② **段规则**：只有 `mixins.json` 的 **非 client 段（common）** 才会在专服上应用。
         所以 common 段的每个 Mixin 只要引用了客户端专属类（import / 简单名 / 内联 FQN /
         `method=` 或 `@At(target=)` 描述符字符串里的 `Lnet/minecraft/client/...;`）⇒ 判问题；
         `client` 段的 Mixin 在专服上**根本不加载**，跳过（绝不误报）。

为什么用「dist 视界」而不是另找一份服务端 jar：本机 Gradle 缓存里**没有**带映射名的
服务端 MC 制品 —— `neoformruntime/artifacts/minecraft_1.21.1_server.jar` 与
`intermediate_results/stripServer_*_output.jar` 都是**混淆名**（连
`net/minecraft/world/inventory/Slot` 都不存在），拿它们比对本工程的 NeoForge 映射名只会
造出另一批假结论。所以服务端一遍 = 同一份 NeoForge 合并产物 + **按 dist 抹掉客户端命名空间**，
并且把「抹掉了哪些类」显式打印出来（可复算、可核对）。

用法
----
    python tools/verify_mixin_shadows.py
    python tools/verify_mixin_shadows.py --mixin-dir <另一个 mixin 目录> \
                                        [--mixins-json <另一个 mixins.json>] [--label <标题后缀>]

缓存判据（2026-10-11 修 —— 这里曾经产生过假绿）
--------------------------------------------
本校验器的结论只对自己实际比对的 jar 成立，因此「看哪个缓存、取哪个版本」必须自证：
  * **缓存根跟随 GRADLE_USER_HOME**（本机 = D:\\gradle ⇒ D:\\gradle\\caches，Gradle 真正在用的
    那一个）。此前硬编码 `C:\\Users\\70432\\.gradle\\caches`，那是另一个根；两个根里的制品版本
    并不一致（升级 RS 到 2.0.9 后本校验器仍比对 2.0.0，却报「0 问题」）。
  * **同族多版本只取一个**：按 (group, artifact, classifier) 分组，优先 gradle.properties 里
    pin 的版本（RS = refinedstorage_version），缓存里没有该版本才退回「mtime 最新」并**显式告警**，
    绝不静默拿别的版本当证据。被排除的版本会打印出来。
  * 反例自证（不改任何文件）：设环境变量 `RSCC_PIN_REFINEDSTORAGE_VERSION=2.0.0`，
    本脚本应打印「使用 2.0.0（来自环境变量…）」。

退出码：0 = 全部通过；1 = 存在问题。
"""

from __future__ import annotations

import glob
import os
import re
import subprocess
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


# ---------------------------------------------------------------------------
# 路径 / 常量
# ---------------------------------------------------------------------------

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MIXIN_DIR = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq",
                         "rs_create_compat", "mixin")
JAVAP = r"D:\java21\bin\javap.exe"

# 缓存根定位 + 同族多版本挑选：唯一实现放在 tools\_gradle_cache.py（与 PowerShell 侧同一套判据）。
# 以前这里写死 `os.path.expanduser("~")/.gradle/caches`，而本机 GRADLE_USER_HOME=D:\gradle
# ⇒ 校验的其实是另一个缓存里的制品 —— 这正是「0 问题」却与真实编译/运行环境不符的来源。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _gradle_cache as gc  # noqa: E402  （必须在 sys.path 调整之后再导入）

# 基本类型 → 描述符
PRIMITIVES = {
    "void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S",
    "int": "I", "long": "J", "float": "F", "double": "D",
}

# java.lang 里常见的、源文件不写 import 也会用到的类型
JAVA_LANG = {
    "String", "Object", "Integer", "Long", "Boolean", "Double", "Float", "Byte",
    "Character", "Short", "Void", "Class", "Throwable", "Exception",
    "RuntimeException", "Enum", "Number", "Iterable", "Comparable", "Record",
    "Override", "Deprecated", "FunctionalInterface", "SuppressWarnings",
}

# 只有这些注解被当作「有意义的 Mixin 注解」参与解析
MEMBER_ANNOTATIONS = {"Shadow", "Inject", "Redirect", "Overwrite", "Accessor",
                      "Invoker", "Final", "Mutable", "Unique", "ModifyVariable",
                      "ModifyArg", "ModifyConstant", "ModifyArgs"}

# 「已知可用」白名单：**极少数**经实测确认 Mixin 能正确定位「继承自父类成员」的场景。
# 键为 "目标二进制类名#成员名"。默认空 —— 正常情况下继承 shadow 一律视为风险并修正。
# （已有的崩案例：mainNetworkNode 声明在泛型祖父类上，子类 @Shadow 直接导致模组加载失败。）
KNOWN_OK_INHERITED_SHADOWS = set()


def is_known_ok_inherited(target: str, member_name: str) -> bool:
    """该「继承成员 shadow / accessor」是否被显式白名单豁免。"""
    return (target + "#" + member_name) in KNOWN_OK_INHERITED_SHADOWS


# ---------------------------------------------------------------------------
# 专用服务端（DEDICATED_SERVER）dist 判据 —— 「两遍校验」的服务端那一遍
# ---------------------------------------------------------------------------

# 两遍的名字（只用于打印，避免散落的字符串字面量写错）
DIST_CLIENT = "CLIENT"
DIST_SERVER = "DEDICATED_SERVER"

# 专用服务端上**确实不存在**的命名空间（= 硬判据，引用即判问题）。
# 判据来源（三条都可复算，脚本运行时会自动打印第 3 条的实测数字）：
#   ① NeoForge 运行期 dist 清理器的报错原文："Attempted to load class {} for invalid dist {}"
#      —— 见 loader-4.0.43.jar!/net/neoforged/fml/common/asm/RuntimeDistCleaner.class。
#   ② 本工程 2026-09 的专服崩溃实证：net.minecraft.client.gui.components.toasts.Toast
#      → NoClassDefFoundError（见 tools/selfcheck_round47_dedicated_server_safety.py 文档）。
#   ③ **制品级实测**（本脚本启动时打印）：
#        neoforge-21.1.248-universal.jar（专服实际分发的那份）:
#            net/minecraft/client/** = 0 个，com/mojang/blaze3d/** = 0 个
#        compiledWithNeoForge_*_output.jar（编译期用的 client+server 合并产物）:
#            net/minecraft/client/** = 2981 个，com/mojang/blaze3d/** = 215 个
#      ⇒ 「编译期能解析、专服却没有」正是本次假绿的来源。
CLIENT_ONLY_PREFIXES = (
    "net.minecraft.client.",
    "com.mojang.blaze3d.",
    "org.lwjgl.",
)

# 软判据：**有风险但制品级证据显示专服上仍然存在**，因此只提示、不判负。
# 为什么单独列出来：`net.neoforged.neoforge.client.**` 里的类随
#   neoforge-*-universal.jar（专服用的就是这一份）一起分发（实测 455 个，
#   例如 net/neoforged/neoforge/client/extensions/IMenuProviderExtension.class），
#   直接当「不存在」会制造假问题。真正的硬约束只有 net.minecraft.client / blaze3d / lwjgl。
CLIENT_RISKY_PREFIXES = (
    "net.neoforged.neoforge.client.",
)


def is_client_only(bin_name):
    """该二进制类名是否属于「专用服务端上确实不存在」的命名空间（硬判据）。"""
    if not bin_name:
        return False
    return bin_name.startswith(CLIENT_ONLY_PREFIXES) or any(
        bin_name == p[:-1] for p in CLIENT_ONLY_PREFIXES)


def is_client_risky(bin_name):
    """该二进制类名是否属于「专服上有风险（但实测仍随 NeoForge 分发）」的命名空间。"""
    if not bin_name:
        return False
    return bin_name.startswith(CLIENT_RISKY_PREFIXES) or any(
        bin_name == p[:-1] for p in CLIENT_RISKY_PREFIXES)


# 点分 FQN 形式（import / 内联 FQN）：net.minecraft.client.gui.screens.Screen
CLIENT_ONLY_FQN_RE = re.compile(
    r"\b((?:" + "|".join(re.escape(p) for p in CLIENT_ONLY_PREFIXES) + r")"
    r"[A-Za-z0-9_$]*(?:\.[A-Za-z0-9_$]+)*)")
# JVM 内部名形式（描述符字符串）：net/minecraft/client/gui/screens/Screen
CLIENT_ONLY_INTERNAL_RE = re.compile(
    r"((?:" + "|".join(re.escape(p.replace(".", "/")) for p in CLIENT_ONLY_PREFIXES) + r")"
    r"[A-Za-z0-9_$/]+)")

# mixins.json 的位置（决定「这段 Mixin 在专服上会不会被加载」）
MIXINS_JSON = os.path.join(ROOT, "src", "main", "resources", "rs_create_compat.mixins.json")


def load_mixin_sections(path=None):
    """读 mixins.json → ({相对类名（不含 .java）: 'common'|'client'}, [备注行])。

    为什么必须知道「段」：`client` 段里的 Mixin 在专用服务端上**根本不会被加载**，
    对它们做 dist 断言只会产生误报；反过来，`common` 段里的 Mixin 专服一定会加载，
    引用了 `net.minecraft.client.**` 就是本次那类「启动即崩」。
    """
    import json
    path = path or MIXINS_JSON
    sections, notes = {}, []
    if not os.path.isfile(path):
        notes.append("[警告] 找不到 %s ⇒ 无法区分 common/client 段" % path)
        return sections, notes
    try:
        with open(path, encoding="utf-8") as handle:
            cfg = json.load(handle)
    except Exception as exc:
        notes.append("[警告] 解析 %s 失败：%s" % (path, exc))
        return sections, notes
    for key, kind in (("mixins", "common"), ("client", "client")):
        for entry in cfg.get(key, []):
            sections[str(entry).replace(".", "/")] = kind
    notes.append("  mixins.json: %s（common 段 %d 个、client 段 %d 个）"
                 % (path, len(cfg.get("mixins", [])), len(cfg.get("client", []))))
    return sections, notes


def client_only_refs(src):
    """源码里对「专服上不存在的命名空间」的引用列表 [(行号, 名字, 形态, 是否硬判据)]。

    注意**保留字符串字面量**（只去注释）：Mixin 的目标经常写在字符串里
    （`method = "...Lnet/minecraft/client/...;"`、`@At(target = "Lnet/minecraft/client/...;")`），
    这些才是真正会让专服崩溃的引用，不能因为「在字符串里」就漏掉。
    """
    hits, seen = [], set()
    for idx, line in enumerate((src.text or "").splitlines(), 1):
        for regex, how in ((CLIENT_ONLY_FQN_RE, "点分 FQN / import"),
                           (CLIENT_ONLY_INTERNAL_RE, "JVM 内部名（描述符字符串）")):
            for m in regex.finditer(line):
                name = m.group(1)
                if (idx, name) in seen:
                    continue
                seen.add((idx, name))
                hits.append((idx, name, how, is_client_only(name)))
    # 软判据（net.neoforged.neoforge.client.**）单独扫一遍，只提示不判负。
    for idx, line in enumerate((src.text or "").splitlines(), 1):
        for prefix in CLIENT_RISKY_PREFIXES:
            for form, how in ((prefix, "点分 FQN / import"),
                              (prefix.replace(".", "/"), "JVM 内部名（描述符字符串）")):
                pos = line.find(form)
                while pos >= 0:
                    end = pos + len(form)
                    while end < len(line) and (line[end].isalnum() or line[end] in "_$./"):
                        end += 1
                    name = line[pos:end].rstrip("./")
                    if (idx, name) not in seen:
                        seen.add((idx, name))
                        hits.append((idx, name, how, False))
                    pos = line.find(form, end)
    return hits


def dist_evidence_lines(cache, mc_jar):
    """用制品本身证明「专服上没有 net.minecraft.client / blaze3d」——可复算的自证。

    为什么要打印：本校验器的服务端一遍是「同一份合并产物 + 按 dist 抹掉客户端命名空间」，
    这个「抹掉」必须能自证不是凭空捏造，否则又变成另一种「看不见的判据」。
    """
    import zipfile
    lines = []
    uni = glob.glob(os.path.join(cache, "modules-2", "files-2.1", "net.neoforged", "neoforge",
                                 "*", "*", "neoforge-*-universal.jar"))
    pairs = (("NeoForge universal（专用服务端实际分发的那份）", sorted(uni)[-1] if uni else None),
             ("compiledWithNeoForge（编译期用的 client+server 合并产物）", mc_jar))
    for label, path in pairs:
        if not path or not os.path.isfile(path):
            lines.append("      %s : <找不到制品>" % label)
            continue
        try:
            with zipfile.ZipFile(path) as zf:
                names = zf.namelist()
            lines.append("      %-52s %s" % (label, os.path.basename(path)))
            lines.append("          net/minecraft/client/** = %d 个, com/mojang/blaze3d/** = %d 个, "
                         "net/neoforged/neoforge/client/** = %d 个"
                         % (sum(1 for n in names if n.startswith("net/minecraft/client/")),
                            sum(1 for n in names if n.startswith("com/mojang/blaze3d/")),
                            sum(1 for n in names if n.startswith("net/neoforged/neoforge/client/"))))
        except Exception as exc:
            lines.append("      %s : 读取失败 %s" % (label, exc))
    return lines


# ---------------------------------------------------------------------------
# 文本工具
# ---------------------------------------------------------------------------

def read_literal(text: str, i: int) -> str:
    """从 i（引号处）读出一个完整字符串/字符字面量，返回其原文。"""
    quote = text[i]
    j = i + 1
    n = len(text)
    while j < n:
        if text[j] == "\\":
            j += 2
            continue
        if text[j] == quote:
            return text[i:j + 1]
        j += 1
    return text[i:]


def looks_like_char_literal(text: str, i: int) -> bool:
    """粗略判断 i 处的单引号是否是字符字面量（而不是别的东西）。"""
    n = len(text)
    for j in range(i + 1, min(i + 4, n)):
        if text[j] == "\\":
            continue
        if text[j] == "'":
            return True
        if text[j] == "\n":
            return False
    return False


def strip_comments(text: str) -> str:
    """去掉 // 与 /* */ 注释，但**保留字符串字面量**（注解参数里要用）。"""
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '"' or (c == "'" and looks_like_char_literal(text, i)):
            lit = read_literal(text, i)
            out.append(lit)
            i += len(lit)
            continue
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def strip_generics(text: str) -> str:
    """抹掉泛型参数（含嵌套），用于结构解析。"""
    out = []
    depth = 0
    for ch in text:
        if ch == "<":
            depth += 1
        elif ch == ">":
            if depth > 0:
                depth -= 1
            else:
                out.append(ch)
        elif depth == 0:
            out.append(ch)
    return "".join(out)


def scan_class(text: str):
    """
    把源文件按大括号层级切开：返回 (类声明原文[含类注解], [成员片段])。

    成员片段只收集「class 体第 1 层」的内容：
      - kind='stmt' ：以 `;` 结尾（字段 / 抽象方法）
      - kind='block'：以 `{` 开始方法体（方法头）
    字符串字面量内部的花括号 / 分号不会被误判。
    """
    depth = 0
    buf = []
    class_header = None
    members = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '"' or (c == "'" and looks_like_char_literal(text, i)):
            lit = read_literal(text, i)
            if depth <= 1:
                buf.append(lit)
            i += len(lit)
            continue
        if c == "{":
            if depth == 0:
                class_header = "".join(buf)
            elif depth == 1:
                members.append(("block", "".join(buf)))
            buf = []
            depth += 1
            i += 1
            continue
        if c == "}":
            depth -= 1
            buf = []
            i += 1
            continue
        if c == ";":
            if depth == 1:
                members.append(("stmt", "".join(buf)))
            buf = []
            i += 1
            continue
        if depth <= 1:
            buf.append(c)
        i += 1
    return class_header, members


def split_annotations(frag: str):
    """
    把片段开头的连续注解剥下来。
    返回 ([(注解简名, 参数原文 or None)], 剩余声明文本)。
    """
    anns = []
    i, n = 0, len(frag)
    while True:
        while i < n and frag[i].isspace():
            i += 1
        if i >= n or frag[i] != "@":
            break
        j = i + 1
        while j < n and (frag[j].isalnum() or frag[j] in "_.$"):
            j += 1
        name = frag[i + 1:j].rsplit(".", 1)[-1]
        args = None
        k = j
        while k < n and frag[k].isspace():
            k += 1
        if k < n and frag[k] == "(":
            depth = 0
            m = k
            while m < n:
                ch = frag[m]
                if ch == '"':
                    m += len(read_literal(frag, m))
                    continue
                if ch == "(":
                    depth += 1
                elif ch == ")":
                    depth -= 1
                    if depth == 0:
                        m += 1
                        break
                m += 1
            args = frag[k + 1:m - 1]
            i = m
        else:
            i = j
        anns.append((name, args))
    return anns, frag[i:]


def split_args(args_raw):
    """把注解参数拆成 (位置参数列表, 具名参数字典)。"""
    if args_raw is None:
        return [], {}
    parts, cur = [], []
    depth = 0
    i, n = 0, len(args_raw)
    while i < n:
        c = args_raw[i]
        if c == '"':
            lit = read_literal(args_raw, i)
            cur.append(lit)
            i += len(lit)
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
            i += 1
            continue
        cur.append(c)
        i += 1
    if "".join(cur).strip():
        parts.append("".join(cur))
    pos, kw = [], {}
    for p in parts:
        p = p.strip()
        m = re.match(r"^([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*(.+)$", p, re.S)
        if m and not p.startswith("@"):
            kw[m.group(1)] = m.group(2).strip()
        else:
            pos.append(p)
    return pos, kw


def join_string_literals(value: str) -> str:
    """把注解里的 "a" + "b" 还原成 ab；非字符串则原样返回。"""
    if value is None:
        return ""
    v = value.strip()
    if v.startswith('"'):
        return "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', v))
    return v


def parse_at_annotation(at_raw):
    """
    把 `at = @At(...)` 的参数原文解析成 (value, target)。

    同时支持两种等价写法：
      * `@At("NEW")`                                → ("NEW", None)
      * `@At(value = "NEW", target = "Lfoo/Bar;")`  → ("NEW", "Lfoo/Bar;")

    注意：`value = "NEW"` 会被 `split_args` 归到具名参数里，因此**不能**只看位置参数
    （旧实现只读位置参数，于是本工程的 @At 一直被解析成 None，等于没校验）。
    """
    if at_raw is None:
        return None, None
    raw = at_raw.strip()
    if raw.startswith("@At"):
        raw = raw[3:].strip()
    if raw.startswith("(") and raw.endswith(")"):
        raw = raw[1:-1]
    pos, kw = split_args(raw)
    value = join_string_literals(kw["value"]) if kw.get("value") else ""
    if not value and pos:
        value = join_string_literals(pos[0])
    target = join_string_literals(kw["target"]) if kw.get("target") else ""
    return (value or None), (target or None)


# --- @At.target 描述符的解析（解析失败返回 None，由调用方报错）---------------

# INVOKE：Lowner;name(形参)返回
AT_MEMBER_RE = re.compile(r"^(L[^;]+;)([A-Za-z_$<][A-Za-z0-9_$<>]*)(\(.*\))(.+)$", re.S)
# FIELD：Lowner;name:类型
AT_FIELD_RE = re.compile(r"^(L[^;]+;)([A-Za-z_$<][A-Za-z0-9_$<>]*):(.+)$", re.S)


def bin_name_from_desc(desc):
    """'Lcom/a/B;' → 'com.a.B'；非对象类型返回 None。"""
    if desc and desc.startswith("L") and desc.endswith(";"):
        return desc[1:-1].replace("/", ".")
    return None


def split_descriptor_params(params_part):
    """
    形参描述符段 → 形参描述符列表。
    接受 '(LA;I)V' / '(LA;I)' / 'LA;I' 三种输入形式。
    """
    s = params_part
    if s.startswith("("):
        depth = 0
        for i, ch in enumerate(s):
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    s = s[1:i]
                    break
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "[":
            j = i
            while j < len(s) and s[j] == "[":
                j += 1
            if j < len(s) and s[j] == "L":
                k = s.find(";", j)
                out.append(s[i:k + 1])
                i = k + 1
            else:
                out.append(s[i:j + 1])
                i = j + 1
        elif c == "L":
            k = s.find(";", i)
            out.append(s[i:k + 1])
            i = k + 1
        else:
            out.append(c)
            i += 1
    return out


def descriptor_params(desc):
    """方法描述符 '(...)R' → 形参描述符列表；不可解析返回 None。"""
    if not desc or not desc.startswith("("):
        return None
    return split_descriptor_params(desc)


def descriptor_return(desc):
    """方法描述符 '(...)R' → 返回类型描述符。"""
    if not desc:
        return None
    depth = 0
    for i, ch in enumerate(desc):
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return desc[i + 1:] or None
    return None


# ---------------------------------------------------------------------------
# 源码类型 → JVM 描述符
# ---------------------------------------------------------------------------

def resolve_type_name(type_src: str, imports: dict, pkg: str):
    """
    把源码里写的类型名解析成二进制类名（点分隔）。
    解析不出来时返回 None。

    嵌套类引用（源码写法 `Outer.Inner`）按二进制名规则还原为 `Outer$Inner`：
    `BlockBehaviour.BlockStateBase` → `net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase`。
    只在外层名能从 import 解析出来时这么做 —— 否则保持原样（全限定名 / 其它写法按原样交给 javap）。
    """
    t = strip_generics(type_src).strip()
    t = re.sub(r"\s+", "", t)
    if not t:
        return None
    if "." in t:
        head, _, tail = t.rpartition(".")
        if tail[:1].isupper() and head in imports:
            return imports[head] + "$" + tail
        # 已写全限定名（或嵌套类引用），直接保留
        return t
    if t in imports:
        return imports[t]
    if t in JAVA_LANG:
        return "java.lang." + t
    # 同包
    return pkg + "." + t


def type_descriptor(type_src: str, imports: dict, pkg: str):
    """源码类型 → JVM 描述符；无法解析时返回 None。"""
    t = strip_generics(type_src).strip()
    t = re.sub(r"\s+", "", t)
    if not t:
        return None
    dims = 0
    while t.endswith("[]"):
        dims += 1
        t = t[:-2]
    if t.endswith("..."):
        t = t[:-3]
        dims += 1
    if t in PRIMITIVES:
        base = PRIMITIVES[t]
    else:
        name = resolve_type_name(t, imports, pkg)
        if name is None:
            return None
        base = "L" + name.replace(".", "/") + ";"
    return "[" * dims + base


def param_descriptors(params_src: str, imports: dict, pkg: str):
    """源码形参表 → 描述符列表；任一段解析失败返回 None。"""
    out = []
    for p in params_src.strip().split(","):
        p = p.strip()
        if not p:
            continue
        p = re.sub(r"^(?:final\s+)+", "", p)
        p = re.sub(r"@[A-Za-z0-9_.$]+(?:\([^)]*\))?\s*", "", p)
        parts = p.split()
        if not parts:
            return None
        # 形参名是最后一段，类型是其余部分
        ptype = " ".join(parts[:-1]) if len(parts) > 1 else parts[0]
        d = type_descriptor(ptype, imports, pkg)
        if d is None:
            return None
        out.append(d)
    return out


def method_descriptor(params_src: str, ret_src: str, imports: dict, pkg: str):
    """源码形参表 + 返回类型 → 方法描述符；任一段解析失败返回 None。"""
    params = param_descriptors(params_src, imports, pkg)
    if params is None:
        return None
    ret = type_descriptor(ret_src, imports, pkg)
    if ret is None:
        return None
    return "(" + "".join(params) + ")" + ret


def accessor_member_name(accessor_name: str):
    """按 Mixin 规则从 get/set/is 前缀推出目标成员名（无显式名字时用）。"""
    for prefix in ("get", "set", "is"):
        if accessor_name.startswith(prefix) and len(accessor_name) > len(prefix):
            rest = accessor_name[len(prefix):]
            if rest[0].isupper():
                return rest[0].lower() + rest[1:]
    return accessor_name


FIELD_RE = re.compile(
    r"^(?P<mods>(?:(?:public|protected|private|static|final|abstract|transient|"
    r"volatile|synchronized|native|strictfp|default)\s+)*)"
    r"(?P<type>.+?)\s+(?P<name>[A-Za-z_$][A-Za-z0-9_$]*)$", re.S)

METHOD_RE = re.compile(
    r"^(?P<mods>(?:(?:public|protected|private|static|final|abstract|synchronized|"
    r"native|strictfp|default)\s+)*)"
    r"(?P<ret>[A-Za-z0-9_$.]+(?:\[\])*)\s+"
    r"(?P<name>[A-Za-z_$][A-Za-z0-9_$]*)\s*\((?P<params>.*)\)"
    r"(?:\s*throws\s+[^{;]+)?$", re.S)


# ---------------------------------------------------------------------------
# javap 类索引
# ---------------------------------------------------------------------------

class JavaClass:
    __slots__ = ("name", "superclass", "interfaces", "fields", "methods")

    def __init__(self, name):
        self.name = name
        self.superclass = None
        self.interfaces = []
        # fields: [(名字, 描述符, 是否 final, 是否 static)]
        self.fields = []
        # methods: [(名字, 描述符, 是否 static)]
        self.methods = []


DESC_LINE_RE = re.compile(r"^\s+descriptor:\s*(\S+)\s*$")


def parse_javap(out: str, bin_name: str) -> JavaClass:
    """解析 `javap -p -s` 的输出。"""
    ci = JavaClass(bin_name)
    lines = out.splitlines()
    simple = bin_name.rsplit(".", 1)[-1].split("$")[-1]
    header = None
    for idx, line in enumerate(lines):
        nxt = lines[idx + 1] if idx + 1 < len(lines) else ""
        dm = DESC_LINE_RE.match(nxt)
        if not dm:
            if header is None and line.rstrip().endswith("{") and \
                    re.search(r"\b(class|interface|enum|record)\b", line):
                header = strip_generics(line.rstrip()[:-1].strip())
            continue
        desc = dm.group(1)
        decl = line.strip()
        if "(" in decl:
            m = re.search(r"([A-Za-z_$][A-Za-z0-9_$.]*)\(([^)]*)\)", decl)
            if not m:
                continue
            raw_name = m.group(1)
            last = raw_name.rsplit(".", 1)[-1]
            name = "<init>" if last == simple else last
            static = " static " in " " + decl + " "
            ci.methods.append((name, desc, static))
        else:
            m = re.search(r"([A-Za-z_$][A-Za-z0-9_$]*)\s*;$", decl)
            if not m:
                continue
            padded = " " + decl + " "
            ci.fields.append((m.group(1), desc, " final " in padded,
                              " static " in padded))
    if header:
        m = re.search(r"\bclass\s+[A-Za-z0-9_$.]+(?:\s+extends\s+([A-Za-z0-9_$.]+))?"
                      r"(?:\s+implements\s+([^{]+))?", header)
        if m:
            ci.superclass = m.group(1) or "java.lang.Object"
            ci.interfaces = [x.strip() for x in (m.group(2) or "").split(",") if x.strip()]
        else:
            m = re.search(r"\binterface\s+[A-Za-z0-9_$.]+(?:\s+extends\s+([^{]+))?", header)
            if m:
                ci.superclass = None
                ci.interfaces = [x.strip() for x in (m.group(1) or "").split(",") if x.strip()]
    return ci


class JarIndex:
    """按类名查真实成员；结果缓存，避免重复调用 javap。

    `dist` 决定「这个索引**看不看得见**客户端专属命名空间」：
      * DIST_CLIENT（默认）：原行为，`net.minecraft.client.**` 正常解析（合并 jar 里本来就有）。
      * DIST_SERVER：属于 CLIENT_ONLY_PREFIXES 的类**一律当作不存在**（返回 None），
        并记进 `self.dist_hidden`。这就是专用服务端的真实视界 —— 于是任何依赖客户端类的
        `@Shadow` / 注入目标 / `@At.target` 都会变成 [缺失] 而不是 [OK]（假绿消除）。
    """

    def __init__(self, classpath, dist=DIST_CLIENT):
        self.classpath = classpath
        self.dist = dist
        self.dist_hidden = []          # [(类名, 是被哪条规则抹掉的)]
        self._cache = {}
        self._failed = {}
        self._warned = set()

    def get(self, bin_name):
        if bin_name in self._cache:
            return self._cache[bin_name]
        if bin_name in self._failed:
            return None
        if self.dist == DIST_SERVER and is_client_only(bin_name):
            # 专服视界：不调用 javap，直接判「不存在」，并把这件事记录下来供输出自证。
            if bin_name not in [n for n, _ in self.dist_hidden]:
                self.dist_hidden.append((bin_name, "dist 清理：专用服务端上不存在"))
            self._failed[bin_name] = "专用服务端上不存在（dist=%s 视界）" % self.dist
            return None
        try:
            proc = subprocess.run(
                [JAVAP, "-p", "-s", "-cp", self.classpath, bin_name],
                capture_output=True, text=True, encoding="utf-8",
                errors="replace", timeout=180)
        except Exception as exc:  # pragma: no cover
            self._failed[bin_name] = str(exc)
            return None
        if proc.returncode != 0 or not proc.stdout:
            self._failed[bin_name] = (proc.stderr or proc.stdout or "").strip()
            return None
        ci = parse_javap(proc.stdout, bin_name)
        self._cache[bin_name] = ci
        return ci

    def warn_unresolved(self, bin_name):
        if bin_name in self._warned:
            return
        self._warned.add(bin_name)
        print("        [提示] 无法读取父类型 %s（%s）" %
              (bin_name, self._failed.get(bin_name, "未找到")))

    def hierarchy(self, bin_name):
        """按 Mixin 的 ClassInfo.getHierarchy() 顺序（自身 → 父类链 → 接口链）返回类名。"""
        order, seen = [], set()

        def walk(name):
            if name in seen or name is None:
                return
            seen.add(name)
            ci = self.get(name)
            if ci is None:
                self.warn_unresolved(name)
                return
            order.append(name)
            walk(ci.superclass)
            for iface in ci.interfaces:
                walk(iface)

        walk(bin_name)
        return order

    # -- 查找 ---------------------------------------------------------------

    def find_field(self, target, name, desc):
        """精确匹配（名字 + 描述符）；返回 (声明类, 是否final) 或 None。"""
        for cls in self.hierarchy(target):
            for fn, fd, fin, _st in self.get(cls).fields:
                if fn == name and fd == desc:
                    return cls, fin
        return None

    def fields_named(self, target, name):
        """返回该名字在层级上的所有 (声明类, 描述符, 是否final)，已按最派生优先去重。"""
        found = {}
        for cls in self.hierarchy(target):
            for fn, fd, fin, _st in self.get(cls).fields:
                if fn == name and (name, fd) not in found:
                    found[(name, fd)] = (cls, fd, fin)
        return list(found.values())

    def methods_named(self, target, name, allow_static=True):
        """
        返回该名字在层级上的所有 (声明类, 描述符)，已按最派生优先去重。

        allow_static=False 时排除静态方法：Mixin 的 TargetSelectors 在
        「非静态注入处理器」下会把静态候选方法直接跳过（见 findRootTargets）。
        """
        found = {}
        for cls in self.hierarchy(target):
            for mn, md, st in self.get(cls).methods:
                if mn != name:
                    continue
                if st and not allow_static:
                    continue
                if (name, md) not in found:
                    found[(name, md)] = (cls, md)
        return list(found.values())

    def find_method(self, target, name, desc, allow_static=True):
        for cls in self.hierarchy(target):
            for mn, md, st in self.get(cls).methods:
                if mn == name and md == desc and (allow_static or not st):
                    return cls, md
        return None


# ---------------------------------------------------------------------------
# 源文件解析
# ---------------------------------------------------------------------------

class MixinSource:
    def __init__(self, path):
        self.path = path
        raw = open(path, encoding="utf-8").read()
        text = strip_comments(raw)
        # 去注释后的源码（**保留字符串字面量**）：服务端一遍要做「客户端专属命名空间」
        # 扫描，描述符字符串里的 Lnet/minecraft/client/...; 也是真实引用，不能漏。
        self.text = text
        self.pkg = ""
        m = re.search(r"^\s*package\s+([A-Za-z0-9_.]+)\s*;", text, re.M)
        if m:
            self.pkg = m.group(1)
        self.imports = {}
        for m in re.finditer(r"^\s*import\s+(?!static\s)([A-Za-z0-9_.$]+)\s*;", text, re.M):
            fqn = m.group(1)
            self.imports[fqn.rsplit(".", 1)[-1]] = fqn
        self.class_header, self.members = scan_class(text)
        header_anns, _decl = split_annotations(self.class_header or "")
        self.class_annotations = header_anns
        # 目标类
        self.target = None
        self.mixin_kind = None
        for name, args in header_anns:
            if name != "Mixin":
                continue
            pos, kw = split_args(args)
            value = kw.get("value") or (pos[0] if pos else None)
            if kw.get("targets"):
                self.mixin_kind = "targets:" + join_string_literals(kw["targets"])
                continue
            if value:
                vm = re.match(r"^([A-Za-z0-9_$.]+)\s*\.class$", value.strip())
                if vm:
                    self.target = resolve_type_name(vm.group(1), self.imports, self.pkg)
        self.class_name = None
        cm = re.search(r"\b(?:class|interface)\s+([A-Za-z_$][A-Za-z0-9_$]*)",
                       strip_generics(self.class_header or ""))
        if cm:
            self.class_name = cm.group(1)

    def rel_name(self):
        return os.path.relpath(self.path, MIXIN_DIR).replace("\\", "/").replace(".java", "")


class ShadowMember:
    """一个 @Shadow / @Accessor / @Invoker 成员。"""

    def __init__(self, kind, name, desc, annotations, type_src, is_final_ann,
                 is_mutable_ann, params_src=None, ret_src=None):
        self.kind = kind              # 'field' / 'method' / 'accessor' / 'invoker'
        self.name = name              # shadow 成员在 Mixin 里写的名字
        self.desc = desc              # 按源码算出的描述符（可能为 None）
        self.annotations = annotations
        self.type_src = type_src      # 供报错展示「代码写的类型/签名」
        self.is_final_ann = is_final_ann
        self.is_mutable_ann = is_mutable_ann
        self.params_src = params_src
        self.ret_src = ret_src
        self.target_member = None     # @Accessor/@Invoker 指向的真实成员名


class InjectionPoint:
    """一个 @Inject / @Redirect / @Overwrite。"""

    def __init__(self, annotation, name, desc, raw, at, cancellable, require, expect,
                 handler_static=False, at_target=None, handler_name=None,
                 handler_ret=None, handler_params=None, handler_ret_src=None,
                 handler_params_src=None):
        self.annotation = annotation
        self.name = name
        self.desc = desc          # None 表示源码只写了方法名
        self.raw = raw
        self.at = at              # @At 的 value（NEW / INVOKE / HEAD / RETURN ...）
        self.cancellable = cancellable
        self.require = require
        self.expect = expect
        self.handler_static = handler_static
        # ---- @Redirect 工厂/调用重定向专用（本轮新增的校验规则要用）----
        self.at_target = at_target            # @At(target = "...") 的原文
        self.handler_name = handler_name      # 处理函数名
        self.handler_ret = handler_ret        # 处理函数返回类型描述符
        self.handler_params = handler_params  # 处理函数形参描述符列表
        self.handler_ret_src = handler_ret_src
        self.handler_params_src = handler_params_src

    @property
    def at_value(self):
        """@At 的 value（self.at 的可读别名，新校验规则里统一用它）。"""
        return self.at


def parse_source(path):
    src = MixinSource(path)
    shadows, injections = [], []

    for _kind, frag in src.members:
        anns, decl = split_annotations(frag)
        if not anns:
            continue
        names = {n for n, _ in anns}
        if not (names & MEMBER_ANNOTATIONS):
            continue
        ann_map = {n: a for n, a in anns}
        struct = strip_generics(decl).strip()
        is_final_ann = "Final" in names
        is_mutable_ann = "Mutable" in names

        # ---- Accessor / Invoker（语法上是方法，但校验对象是目标字段/方法）----
        if names & {"Accessor", "Invoker"}:
            mm = METHOD_RE.match(struct) if "(" in struct else None
            if not mm:
                continue
            is_accessor = "Accessor" in names
            pds = param_descriptors(mm.group("params"), src.imports, src.pkg)
            rds = type_descriptor(mm.group("ret"), src.imports, src.pkg)
            if pds is None or rds is None:
                desc = None
            elif is_accessor:
                # getter 比字段类型；setter（1 参 + void）比参数类型
                desc = rds if not pds else (pds[0] if len(pds) == 1 else None)
            else:
                desc = "(" + "".join(pds) + ")" + rds
            member = ShadowMember("accessor" if is_accessor else "invoker",
                                  mm.group("name"), desc, anns, decl,
                                  is_final_ann, is_mutable_ann,
                                  mm.group("params"), mm.group("ret"))
            a_pos, a_kw = split_args(ann_map.get("Accessor") or ann_map.get("Invoker"))
            raw = (a_pos[0].strip() if a_pos else None) or a_kw.get("value")
            member.target_member = (join_string_literals(raw) if raw
                                    else accessor_member_name(mm.group("name")))
            shadows.append(member)
            continue

        # ---- Shadow ----
        if "Shadow" in names:
            mm = METHOD_RE.match(struct) if "(" in struct else None
            if mm:
                name = mm.group("name")
                if name == src.class_name:
                    continue  # 构造函数，@Shadow 不适用
                ret_src = mm.group("ret")
                params_src = mm.group("params")
                desc = method_descriptor(params_src, ret_src, src.imports, src.pkg)
                shadows.append(ShadowMember("method", name, desc, anns, decl,
                                            is_final_ann, is_mutable_ann,
                                            params_src, ret_src))
                continue
            mf = FIELD_RE.match(struct)
            if not mf:
                continue
            type_src = mf.group("type")
            desc = type_descriptor(type_src, src.imports, src.pkg)
            shadows.append(ShadowMember("field", mf.group("name"), desc, anns, type_src,
                                        is_final_ann, is_mutable_ann))
            continue

        # ---- Inject / Redirect / Overwrite / ModifyXXX ----
        inject_names = [n for n in ("Inject", "Redirect", "Overwrite", "ModifyVariable",
                                    "ModifyArg", "ModifyArgs", "ModifyConstant")
                        if n in names]
        if not inject_names:
            continue
        ann_name = inject_names[0]
        pos, kw = split_args(ann_map.get(ann_name))
        head = struct[:struct.index("(")] if "(" in struct else struct
        handler_static = bool(re.search(r"\bstatic\b", head))
        raw_method = join_string_literals(kw.get("method")) if kw.get("method") else ""
        if not raw_method:
            # @Overwrite 没有 method 属性：目标就是本方法自身（按名字 + 描述符校验）
            mm = METHOD_RE.match(struct) if "(" in struct else None
            if not mm or mm.group("name") == src.class_name:
                continue
            mdesc = method_descriptor(mm.group("params"), mm.group("ret"),
                                      src.imports, src.pkg)
            injections.append(InjectionPoint(ann_name, mm.group("name"), mdesc, decl,
                                             None, None, kw.get("require"),
                                             kw.get("expect"), handler_static))
            continue
        mname, mdesc = raw_method, None
        if "(" in raw_method:
            mname = raw_method[:raw_method.index("(")]
            mdesc = raw_method[raw_method.index("("):]
        at_name = None
        at_target = None
        if kw.get("at"):
            at_name, at_target = parse_at_annotation(kw["at"])
        elif pos:
            at_name = join_string_literals(pos[0])
        # 处理函数自己的签名（本轮新增：@Redirect 的返回类型/形参必须与 @At 目标匹配）。
        # 对 @Overwrite 而言「处理函数」就是被覆写的方法本身，签名校验另有 @Shadow 逻辑，这里只对
        # @Redirect 采集（其它注解即便采到也不会用到）。
        handler_name = handler_ret = handler_params = None
        handler_ret_src = handler_params_src = None
        hmm = METHOD_RE.match(struct) if "(" in struct else None
        if hmm and hmm.group("name") != src.class_name:
            handler_name = hmm.group("name")
            handler_ret_src = hmm.group("ret")
            handler_params_src = hmm.group("params")
            handler_ret = type_descriptor(handler_ret_src, src.imports, src.pkg)
            handler_params = param_descriptors(handler_params_src, src.imports, src.pkg)
        injections.append(InjectionPoint(ann_name, mname, mdesc, decl, at_name,
                                         kw.get("cancellable"), kw.get("require"),
                                         kw.get("expect"), handler_static,
                                         at_target=at_target, handler_name=handler_name,
                                         handler_ret=handler_ret, handler_params=handler_params,
                                         handler_ret_src=handler_ret_src,
                                         handler_params_src=handler_params_src))

    return src, shadows, injections


# ---------------------------------------------------------------------------
# 校验
# ---------------------------------------------------------------------------

def shorten_owner(bin_name):
    return bin_name.rsplit(".", 1)[-1]


def check_shadow(shadow, target, index, out):
    """返回问题数。"""
    issues = 0
    ann_note = "".join(" @" + n for n, _ in shadow.annotations
                       if n in ("Final", "Mutable"))
    if shadow.kind == "field":
        if shadow.desc is None:
            out.append("[无法解析] @Shadow 字段 %s：源码类型「%s」解析不出类名"
                       % (shadow.name, shadow.type_src))
            return 1
        hit = index.find_field(target, shadow.name, shadow.desc)
        if hit:
            owner, real_final = hit
            if owner != target and not is_known_ok_inherited(target, shadow.name):
                issues += 1
                out.append("[风险] @Shadow 字段 %s (%s) 声明于父类型「%s」，不是目标类「%s」自身"
                           % (shadow.name, shadow.desc, owner, target))
                out.append("        说明：@Shadow 可能无法定位「继承自父类」的字段，"
                           "会在模组加载期抛 InvalidMixinException 直接崩游戏")
                out.append("        修法：改为在声明类上挂 @Accessor（@Mixin(%s.class) + @Accessor(\"%s\")），"
                           "或改用公开 API / 公开 getter" % (shorten_owner(owner), shadow.name))
                return issues
            note = ann_note
            if shadow.is_final_ann and not real_final:
                note += "  [提示] 真实字段并非 final"
            if shadow.is_mutable_ann and not shadow.is_final_ann:
                note += "  [提示] @Mutable 未与 @Final 搭配"
            out.append("[OK]    @Shadow 字段 %-22s %-66s (声明于 %s)%s"
                       % (shadow.name, shadow.desc, shorten_owner(owner), note))
            return 0
        cands = index.fields_named(target, shadow.name)
        if cands:
            issues += 1
            out.append("[描述符不符] @Shadow 字段 %s" % shadow.name)
            out.append("        代码写的类型 : %s  →  %s"
                       % (shadow.type_src.strip(), shadow.desc))
            for owner, fd, fin in cands:
                out.append("        真实描述符   : %s   (声明于 %s%s)"
                           % (fd, shorten_owner(owner), "，final" if fin else ""))
            out.append("        修法：把字段类型改成与「真实描述符」一致的类型（泛型擦除后的上界）")
            return issues
        issues += 1
        out.append("[缺失] @Shadow 字段 %s（%s）在 %s 的层级上不存在"
                   % (shadow.name, shadow.desc, shorten_owner(target)))
        return issues

    # ---- 方法 / 访问器 ----
    if shadow.kind in ("accessor", "invoker"):
        member_name = shadow.target_member or shadow.name
        is_field = shadow.kind == "accessor"
        cands = (index.fields_named(target, member_name) if is_field
                 else index.methods_named(target, member_name))
        cands = [(o, d) for o, d, *_rest in cands]
        label = "@Accessor" if is_field else "@Invoker"
        what = "字段" if is_field else "方法"
        if not cands:
            issues += 1
            out.append("[缺失] %s 目标%s %s 在 %s 的层级上不存在"
                       % (label, what, member_name, shorten_owner(target)))
            return issues
        if shadow.desc is not None and any(d == shadow.desc for _o, d in cands):
            owner = next(o for o, d in cands if d == shadow.desc)
            if owner != target and not is_known_ok_inherited(target, member_name):
                issues += 1
                out.append("[风险] %s %s → 目标%s %s (%s) 声明于父类型「%s」，不是目标类「%s」自身"
                           % (label, shadow.name, what, member_name, shadow.desc, owner, target))
                out.append("        说明：@Accessor/@Invoker 只能访问其 @Mixin 目标类自身的成员；"
                           "继承成员需把注解挂到「声明类」上")
                return issues
            out.append("[OK]    %-9s %-26s → %s %-22s %-50s (声明于 %s)"
                       % (label, shadow.name, what, member_name, shadow.desc,
                          shorten_owner(owner)))
            return 0
        issues += 1
        out.append("[描述符不符] %s %s → %s %s" % (label, shadow.name, what, member_name))
        out.append("        代码写的类型 : %s  →  %s"
                   % (shadow.type_src.strip(), shadow.desc))
        for owner, d in cands:
            out.append("        真实描述符   : %s   (声明于 %s)" % (d, shorten_owner(owner)))
        return issues

    if shadow.desc is None:
        out.append("[无法解析] @Shadow 方法 %s：签名「%s」解析不出描述符"
                   % (shadow.name, shadow.ret_src))
        return 1
    sig = "%s(%s)" % (shadow.name, shadow.params_src.strip())
    hit = index.find_method(target, shadow.name, shadow.desc)
    if hit:
        owner, _ = hit
        if owner != target and not is_known_ok_inherited(target, shadow.name):
            issues += 1
            out.append("[风险] @Shadow 方法 %s (%s) 声明于父类型「%s」，不是目标类「%s」自身"
                       % (sig, shadow.desc, owner, target))
            out.append("        说明：@Shadow 可能无法定位「继承自父类」的方法，"
                       "会在模组加载期抛 InvalidMixinException 直接崩游戏")
            out.append("        修法：改为在声明类上挂 @Accessor/@Invoker，或改用公开 API")
            return issues
        out.append("[OK]    @Shadow 方法 %-40s %-60s (声明于 %s)"
                   % (sig, shadow.desc, shorten_owner(owner)))
        return 0
    cands = index.methods_named(target, shadow.name)
    if cands:
        issues += 1
        out.append("[描述符不符] @Shadow 方法 %s" % sig)
        out.append("        代码写的签名 : %s  →  %s" % (sig, shadow.desc))
        for owner, md in cands:
            out.append("        真实描述符   : %s(%s)   (声明于 %s)"
                       % (shadow.name, md[1:-1], shorten_owner(owner)))
        return issues
    issues += 1
    out.append("[缺失] @Shadow 方法 %s（%s）在 %s 的层级上不存在"
               % (sig, shadow.desc, shorten_owner(target)))
    return issues


def check_injection(inj, target, index, out):
    """返回问题数。"""
    if not inj.name:
        out.append("[待人工确认] @%s 未解析出目标方法名（源码：%s）"
                   % (inj.annotation, inj.raw.replace("\n", " ")))
        return 1
    extra = ""
    if inj.at:
        extra += " @At(\"%s\")" % inj.at
    if inj.cancellable:
        extra += " cancellable"
    if inj.require is not None:
        extra += " require=%s" % inj.require
    if inj.expect is not None:
        extra += " expect=%s" % inj.expect

    def emit(owner, md, ok):
        """输出一条注入目标结论；ok=False 表示「声明类不是目标类自身」→ 计为风险。"""
        line = "@%-9s %s%s  (声明于 %s)" % (inj.annotation, inj.name + md, extra,
                                            shorten_owner(owner))
        if ok:
            out.append("[OK]    " + line)
            return 0
        out.append("[风险]  " + line)
        out.append("        说明：@%s 只能注入「@Mixin 目标类自身声明」的方法；%s 声明于父类型「%s」，"
                   "Mixin 定位不到该目标，会在客户端打开界面 / 模组加载期抛 "
                   "InvalidInjectionException 直接崩游戏" % (inj.annotation, inj.name, owner))
        out.append("        修法：改为注入「目标类自身声明」的方法（如 init，或该子类自己覆写的方法），"
                   "或用自绘控件 / 事件等机制替代这次注入")
        return 1

    cands = index.methods_named(target, inj.name, allow_static=inj.handler_static)
    if not cands:
        out.append("[缺失] @%s 目标方法 %s 在 %s 的层级上不存在%s"
                   % (inj.annotation, inj.name, shorten_owner(target), extra))
        return 1

    if inj.desc:
        for owner, md in cands:
            if md == inj.desc:
                return emit(owner, md, owner == target)
        out.append("[描述符不符] @%s 目标方法 %s" % (inj.annotation, inj.name))
        out.append("        代码写的描述符 : %s" % inj.desc)
        for owner, md in cands:
            out.append("        真实描述符     : %s   (声明于 %s)"
                       % (md, shorten_owner(owner)))
        return 1

    declared = [(o, md) for o, md in cands if o == target]
    if len(declared) == 1:
        return emit(declared[0][0], declared[0][1], True)
    if not declared:
        owner, md = cands[0]
        return emit(owner, md, False)
    out.append("[歧义] @%s 目标方法只写了名字 %s，但目标类「%s」自身有 %d 个重载：%s"
               % (inj.annotation, inj.name, shorten_owner(target), len(declared),
                  ", ".join(inj.name + md for _o, md in declared)))
    out.append("        修法：在 method 里补上精确描述符，例如 method = \"%s%s\""
               % (inj.name, declared[0][1]))
    return 1


# ---------------------------------------------------------------------------
# @Redirect 工厂 / 调用重定向 handler 签名校验（2026-10-11 新增）
#
# 为什么必须有这一节：旧版只校验「被注入的目标方法是否存在」，于是 1.1.0 的
#   @At(value = "NEW", target = "...PatternSlot;")  +  handler 返回 Slot
# 被判为 [OK]、总问题数 0，而实机模组加载期直接抛 InvalidInjectionException（崩启动）。
# 规则来源：Mixin 0.8.7 @Redirect 官方 javadoc（Redirect.html，gradle 缓存里的
#   net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7）——「The handler method signature must
#   match the constructor being redirected and the return type must match the type of object
#   being constructed」，并允许把目标方法形参**追加**在 handler 形参末尾。
# ---------------------------------------------------------------------------

REDIRECT_FIELD_MODES = {"FIELD", "GETFIELD", "PUTFIELD", "GETSTATIC", "PUTSTATIC"}
REDIRECT_CHECKED_MODES = {"NEW", "INVOKE", "INSTANCEOF"} | REDIRECT_FIELD_MODES


def _fmt_params(types):
    return "(" + "".join(types or []) + ")"


def _prefix_and_tail_ok(handler_params, prefix, target_method_params):
    """handler 形参 == prefix +（可选）目标方法形参的前缀 → 是否成立。"""
    if len(handler_params) < len(prefix):
        return False
    if list(handler_params[:len(prefix)]) != list(prefix):
        return False
    tail = list(handler_params[len(prefix):])
    if not tail:
        return True
    if target_method_params is None:
        # 目标方法真实形参取不到时**不判负**：目标方法本身若已缺失/不可解析，
        # check_injection 那条规则已经报过一次，这里再报只会产生噪声。
        return True
    return tail == list(target_method_params[:len(tail)])


def validate_redirect_signature(at_value, at_target, handler_ret, handler_params,
                                target_method_params, real=None, lenient_params=False):
    """
    纯函数：按 Mixin 的 @Redirect 规则校验 handler 签名，返回「问题描述」列表（空 = 通过）。

    刻意**不读 jar / 不调 javap**：所有「真实字节码」事实由 real 传入，因此
    tools/selfcheck_round64_redirect_factory_signature.py 可以用崩溃日志里那一对签名
    （Slot vs PatternSlot）直接构造反例，在不启动游戏的前提下证明检查有效。
      real["ctor_candidates"] : NEW    —— 真实构造器的形参列表（可能多个重载）
      real["invoke_static"]   : INVOKE —— 被调用方法是否 static（True/False/None=未知）
      real["field_static"]    : FIELD  —— 字段是否 static（True/False/None=未知）
    target_method_params 为 None 表示「目标方法真实形参未知」。
    lenient_params=True 时跳过形参规则、只保留返回类型规则（handler 形参上挂了 @Coerce，
    声明的类型本来就可以与栈上类型不同；见 Mixin javadoc「All arguments of a method redirect
    handler, and the return type, can be decorated with @Coerce」）。
    注意：各分支里的 `not lenient_params and ...` 是刻意写开的 —— 曾经用「局部函数覆盖
    _prefix_and_tail_ok」的写法，结果 lenient_params=False 时该名字变成未绑定的局部变量，
    生成器表达式直接抛 NameError（被 selfcheck_round64 抓到）。
    """
    real = real or {}
    mode = (at_value or "").upper()
    if handler_ret is None or handler_params is None:
        return ["handler 签名解析不出描述符（返回类型/形参里出现了源码无法解析的类型）"]
    problems = []

    if mode == "NEW":
        if not at_target:
            return ["@At(value = \"NEW\") 没有写 target：无法得知被构造的类型（Mixin 也不会接受）"]
        if "(" in at_target:
            constructed = descriptor_return(at_target)
            ctor_cands = [descriptor_params(at_target) or []]
        else:
            constructed = at_target
            ctor_cands = real.get("ctor_candidates")
        if not constructed or not constructed.startswith("L"):
            return ["无法从 @At.target「%s」解析出被构造的类型（应为 Lowner;，或含构造器形参的"
                    " (形参)Lowner; 形式）" % at_target]
        if handler_ret != constructed:
            problems.append(
                "返回类型不符：handler 返回 %s，被构造的却是 %s"
                "（NEW 工厂模式下 Mixin 要求 handler 返回类型与被构造类型**精确相等**）"
                % (handler_ret, constructed))
        if ctor_cands and not lenient_params:
            if not any(_prefix_and_tail_ok(handler_params, c, target_method_params)
                       for c in ctor_cands):
                problems.append(
                    "形参不符：handler 形参 %s；可接受的是「构造器形参 +（可选）目标方法形参前缀」，"
                    "真实构造器形参为 %s"
                    % (_fmt_params(handler_params),
                       " 或 ".join(_fmt_params(c) for c in ctor_cands[:3])))
        return problems

    if mode == "INVOKE":
        m = AT_MEMBER_RE.match(at_target or "")
        if not m:
            return ["无法解析 @At.target「%s」（INVOKE 需要 Lowner;name(形参)返回 形式）" % at_target]
        owner_desc, mname, params_part, ret = m.group(1), m.group(2), m.group(3), m.group(4)
        invoke_params = descriptor_params(params_part) or []
        if mname == "<init>":
            # @At 指向构造器时按 NEW 工厂语义：被构造类型 = owner，形参 = 构造器形参（无 owner 实例）
            if handler_ret != owner_desc:
                problems.append("返回类型不符：handler 返回 %s，被构造的是 %s（@At 指向 <init>，"
                                "按 NEW 工厂语义校验）" % (handler_ret, owner_desc))
            expected = [[invoke_params]]
        else:
            if handler_ret != ret:
                problems.append("返回类型不符：handler 返回 %s，被重定向的方法返回 %s"
                                "（Mixin 要求二者精确相等）" % (handler_ret, ret))
            static = real.get("invoke_static")
            if static is False:
                expected = [[owner_desc] + invoke_params]
            elif static is True:
                expected = [invoke_params]
            else:
                # 静态性未知（真实字节码没查到）：两种排布都接受，绝不因此误报
                expected = [[owner_desc] + invoke_params, invoke_params]
        if not lenient_params and not any(_prefix_and_tail_ok(handler_params, p, target_method_params)
                                          for p in expected):
            problems.append("形参不符：handler 形参 %s；可接受的是 %s"
                            % (_fmt_params(handler_params),
                               " 或 ".join(_fmt_params(p) for p in expected)))
        return problems

    if mode in REDIRECT_FIELD_MODES:
        m = AT_FIELD_RE.match(at_target or "")
        if not m:
            return ["无法解析 @At.target「%s」（字段重定向需要 Lowner;name:类型 形式）" % at_target]
        owner_desc, fname, ftype = m.group(1), m.group(2), m.group(3)
        is_read = mode in ("FIELD", "GETFIELD", "GETSTATIC")
        expect_ret = ftype if is_read else "V"
        if handler_ret != expect_ret:
            problems.append("返回类型不符：handler 返回 %s，而 %s 重定向要求返回 %s"
                            % (handler_ret, mode, expect_ret))
        if mode == "GETSTATIC":
            expected = [[]]
        elif mode == "GETFIELD":
            expected = [[owner_desc]]
        elif mode == "PUTSTATIC":
            expected = [[ftype]]
        elif mode == "PUTFIELD":
            expected = [[owner_desc, ftype]]
        else:  # 泛化的 FIELD：读/写与静态性由真实指令决定，两种排布都接受
            expected = ([[], [owner_desc]] if is_read else [[ftype], [owner_desc, ftype]])
        if not lenient_params and not any(_prefix_and_tail_ok(handler_params, p, target_method_params)
                                          for p in expected):
            problems.append("形参不符：handler 形参 %s；可接受的是 %s"
                            % (_fmt_params(handler_params),
                               " 或 ".join(_fmt_params(p) for p in expected)))
        return problems

    if mode == "INSTANCEOF":
        if handler_ret not in ("Z", "Ljava/lang/Class;"):
            problems.append("返回类型不符：instanceof 重定向的 handler 必须返回 boolean（Z）或 "
                            "Class（Ljava/lang/Class;），实际 %s" % handler_ret)
        if not lenient_params and list(handler_params) != ["Ljava/lang/Object;", "Ljava/lang/Class;"]:
            problems.append("形参不符：instanceof 重定向的 handler 形参固定为 "
                            "(Ljava/lang/Object;Ljava/lang/Class;)，实际 %s"
                            % _fmt_params(handler_params))
        return problems

    return []  # 其余 @At（HEAD/RETURN/CONSTANT/...）本就不是重定向目标，无需校验


def _find_method_ex(index, target, name, desc):
    """在真实字节码里找方法，返回 (声明类, 描述符, 是否 static) 或 None。"""
    for cls in index.hierarchy(target):
        for mn, md, st in index.get(cls).methods:
            if mn == name and md == desc:
                return cls, md, st
    return None


def _find_field_ex(index, target, name, desc):
    """在真实字节码里找字段，返回 (声明类, 描述符, 是否 static) 或 None。"""
    for cls in index.hierarchy(target):
        for fn, fd, _fin, st in index.get(cls).fields:
            if fn == name and fd == desc:
                return cls, fd, st
    return None


def check_redirect_handler(inj, target, index, out):
    """
    @Redirect 专项：返回类型 / 形参 / @At.target 与真实字节码一致性。返回问题数。

    这三条是 2026-10-11「专用服务端启动即崩」暴露出来的盲区（旧版只查目标方法存在与否）。
    通用实现：不针对某个 mixin 特判，任何 @Redirect 都会走这里。
    """
    if inj.annotation != "Redirect":
        return 0
    if inj.handler_ret is None or inj.handler_params is None:
        out.append("[无法解析] @Redirect 处理函数 %s 的签名解析不出描述符（源码：%s）"
                   % (inj.handler_name or "<未解析>", inj.raw.replace("\n", " ")))
        return 1
    mode = (inj.at_value or "").upper()
    head = "@Redirect %s%s → handler %s %s" % (
        inj.name, inj.desc or "", inj.handler_name,
        "(%s)%s" % (",".join(inj.handler_params), inj.handler_ret))
    if mode not in REDIRECT_CHECKED_MODES:
        out.append("[提示] %s：@At 的 value 解析为「%s」，不在 NEW/INVOKE/FIELD/INSTANCEOF 之列，"
                   "跳过 handler 签名校验" % (head, inj.at_value))
        return 0

    # 目标方法真实形参（供「可选追加目标方法形参」规则用；取不到则为 None = 不判负）
    tparams = None
    if inj.desc:
        hit = index.find_method(target, inj.name, inj.desc)
        if hit is not None:
            tparams = descriptor_params(hit[1])
    else:
        cands = index.methods_named(target, inj.name)
        if len(cands) == 1:
            tparams = descriptor_params(cands[0][1])
    # MixinExtras 的 @Local/@Share/@Definition 形参会追加在标准形参之后，本校验器不解析它们
    # ⇒ 出现这类注解时只保留「前缀（构造器/被调用方法的实参）」规则，放宽尾部规则，避免误报。
    params_src = inj.handler_params_src or ""
    if re.search(r"@(?:Local|Share|Definition)\b", params_src):
        tparams = None
    # @Coerce 可以让声明类型与栈上类型不同 ⇒ 形参规则整条跳过（返回类型仍然严格校验）
    lenient_params = "@Coerce" in params_src

    # ---- @At.target 与真实字节码一致性 -------------------------------------
    real, bin_issues = {}, []
    at_target = inj.at_target or ""
    if mode == "NEW":
        owner_desc = descriptor_return(at_target) if "(" in at_target else at_target
        bin_name = bin_name_from_desc(owner_desc or "")
        ci = index.get(bin_name) if bin_name else None
        if ci is None:
            bin_issues.append("@At.target 指向的类在真实字节码里不存在：%s" % at_target)
        else:
            ctors = [md for mn, md, _st in ci.methods if mn == "<init>"]
            real["ctor_candidates"] = [descriptor_params(md) or [] for md in ctors]
            if "(" in at_target:
                want = "(" + "".join(descriptor_params(at_target) or []) + ")V"
                if want not in ctors:
                    bin_issues.append("@At.target 写的构造器 %s 在 %s 里不存在；真实构造器：%s"
                                      % (want, bin_name, ", ".join(ctors) or "无"))
    elif mode == "INVOKE":
        m = AT_MEMBER_RE.match(at_target)
        if not m:
            bin_issues.append("@At.target 不是合法的方法描述符：%s" % at_target)
        else:
            owner_bin = bin_name_from_desc(m.group(1))
            full = m.group(3) + m.group(4)
            hit2 = _find_method_ex(index, owner_bin, m.group(2), full) if owner_bin else None
            if hit2 is None:
                bin_issues.append("@At.target 指向的方法在真实字节码里不存在：%s.%s%s"
                                  % (owner_bin, m.group(2), full))
            else:
                real["invoke_static"] = hit2[2]
    elif mode in REDIRECT_FIELD_MODES:
        m = AT_FIELD_RE.match(at_target)
        if not m:
            bin_issues.append("@At.target 不是合法的字段描述符：%s" % at_target)
        else:
            owner_bin = bin_name_from_desc(m.group(1))
            hit2 = _find_field_ex(index, owner_bin, m.group(2), m.group(3)) if owner_bin else None
            if hit2 is None:
                bin_issues.append("@At.target 指向的字段在真实字节码里不存在：%s.%s:%s"
                                  % (owner_bin, m.group(2), m.group(3)))
            else:
                real["field_static"] = hit2[2]

    problems = validate_redirect_signature(mode, at_target, inj.handler_ret,
                                          inj.handler_params, tparams, real,
                                          lenient_params=lenient_params) + bin_issues
    if not problems:
        out.append("[OK]    @Redirect %s%s → handler %s：返回 %s、形参 %s，与 @At(%s) 目标一致"
                   % (inj.name, inj.desc or "", inj.handler_name,
                      inj.handler_ret, _fmt_params(inj.handler_params), mode))
        return 0
    out.append("[签名不符] %s" % head)
    out.append("        handler 源码签名 : %s %s(%s)"
               % (inj.handler_ret_src, inj.handler_name, inj.handler_params_src or ""))
    for p in problems:
        out.append("        " + p)
    out.append("        说明：Mixin 在**模组加载期**校验 @Redirect 的 handler 签名，不符即抛 "
               "InvalidInjectionException（服务端启动即崩 / 客户端崩），必须静态拦住")
    out.append("        规则：NEW 工厂 handler 的返回类型必须精确等于被构造的类型，形参 = 构造器形参"
               "（可再追加目标方法形参的前缀）；@At.target 必须与真实字节码一致")
    return 1


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def _fatal(title, notes, hint):
    """统一的致命错误出口：把「期望位置 / 用的是哪个缓存」一并打出来，避免看不清失败原因。"""
    print("[FATAL] %s" % title)
    for line in notes:
        print("        %s" % line)
    print("        提示：%s" % hint)
    sys.exit(2)


def find_mc_jar(cache):
    """MC 编译产物；多份候选（不同 parchment 映射批次）时取 mtime 最新并打印选了哪一份。"""
    jar, notes = gc.find_mc_jar(root=cache)
    if jar is None:
        _fatal("找不到 Minecraft 编译产物（neoformruntime intermediate_results）", notes,
               "先跑一次 gradle 构建以生成 compiledWithNeoForge_*_output.jar")
    return jar, notes


def find_create_jar(cache):
    """Create jar：本模组把 Create 作为硬依赖，且 create/FluidPipeBlockMixin 的
    目标类（com.simibubi.create.content.fluids.pipes.FluidPipeBlock）声明在 Create 里。
    不把 Create 放进 classpath，这类 Mixin 的目标会被一律误判成「缺失」（假报警）。

    按坐标 + pin 版本（gradle.properties:create_version）精确取，而不是「glob 里 mtime 最新」：
    否则同族多版本并存时，取到哪一版取决于目录/时间，结论就不可复现。"""
    jar, notes = gc.find_jar("com.simibubi.create", "create-1.21.1", classifier="slim",
                             root=cache)
    if jar is None:
        _fatal("找不到 Create jar（com.simibubi.create:create-1.21.1）", notes,
               "先跑一次 gradle 依赖解析以填充本地缓存")
    return jar, notes


def pinned_rs_version():
    """（保留的兼容入口）读出本工程编译基线所对的 RS 版本（gradle.properties:refinedstorage_version）。"""
    pin = gc.pinned_versions().get("com.refinedmods.refinedstorage:refinedstorage-neoforge")
    return pin[0] if pin else None


def find_rs_jar(cache):
    """RS jar：按坐标 + pin 版本精确选，找不到 pin 版本时由 _gradle_cache 显式告警后回退。"""
    jar, notes = gc.find_jar("com.refinedmods.refinedstorage", "refinedstorage-neoforge",
                             classifier="", root=cache)
    if jar is None:
        _fatal("找不到 Refined Storage jar"
               "（com.refinedmods.refinedstorage:refinedstorage-neoforge）", notes,
               "先跑一次 gradle 依赖解析以填充本地缓存")
    return jar, notes


def _cli_option(name, default):
    """极简 CLI 选项解析（`--opt value` 或 `--opt=value`）。

    存在的理由：反例自证要在**不改任何业务源码**的前提下跑一遍「坏签名」的 mixin 目录，
    所以必须能把 Mixin 目录 / mixins.json 指到临时副本上。
    """
    argv = sys.argv[1:]
    for i, a in enumerate(argv):
        if a == name and i + 1 < len(argv):
            return argv[i + 1]
        if a.startswith(name + "="):
            return a.split("=", 1)[1]
    return default


def run_pass(title, dist, files, sections, index):
    """跑**一遍**完整校验并逐条打印；返回 (问题总数, 统计 dict, 有问题的文件列表)。

    两遍共用同一份源码与同一套 @Shadow / 注入目标规则，唯一区别是：
      * `index.dist` —— 服务端一遍把客户端专属命名空间当作不存在；
      * 服务端一遍额外做「common 段的 Mixin 不得引用客户端专属类」的断言，
        并**跳过** client 段的 Mixin（专服不加载它们）。
    """
    print("")
    print("=" * 100)
    print("【%s】" % title)
    print("=" * 100)
    stats = {"files": 0, "shadow_field": 0, "shadow_method": 0, "inject": 0,
             "redirect": 0, "redirect_checked": 0, "skipped_client": 0}
    total = 0
    problem_files = []

    for path in files:
        src, shadows, injections = parse_source(path)
        rel = src.rel_name()
        section = sections.get(rel, "unregistered")
        section_text = {"common": "common（专服会加载）",
                        "client": "client（专服不加载）"}.get(section, "未登记")
        stats["files"] += 1
        print("")
        print("--- %s" % rel)
        if dist == DIST_SERVER:
            print("    mixins.json 段 : %s" % section_text)
        if not src.target:
            print("    目标类 : <未解析到 @Mixin(Target.class)%s>"
                  % ("，kind=" + src.mixin_kind if src.mixin_kind else ""))
            continue
        print("    目标类 : %s" % src.target)

        out = []
        issues = 0

        # ---- dist 扫描：这一节是「服务端一遍」存在的理由 ----
        refs = client_only_refs(src)
        hard_refs = [r for r in refs if r[3]]
        soft_refs = [r for r in refs if not r[3]]
        if dist == DIST_SERVER and section != "client" and hard_refs:
            issues += 1
            out.append("[专服 dist 违规] 该 Mixin 登记在 mixins.json 的「%s」段"
                       "（专用服务端**会**加载它），却引用了专服上确实不存在的客户端专属类："
                       % section_text)
            for line_no, name, how, _hard in hard_refs[:10]:
                out.append("        第 %s 行：%s  （%s）" % (line_no, name, how))
            if len(hard_refs) > 10:
                out.append("        … 另有 %d 处" % (len(hard_refs) - 10))
            out.append("        运行期后果：加载即抛 \"Attempted to load class ... for invalid dist "
                       "DEDICATED_SERVER\"（或 NoClassDefFoundError）⇒ 模组加载失败、专服启动即崩")
            out.append("        修法：把该 Mixin 移到 mixins.json 的 client 段；"
                       "或去掉对客户端类型的引用（改走事件 / 公开 API）")
        elif refs:
            # 客户端一遍（或服务端一遍但只有软判据命中）：不计问题，只提示。
            note = "客户端语境下正常" if dist == DIST_CLIENT else "专服上有风险但实测仍随 NeoForge 分发，不判负"
            out.append("[INFO] 引用客户端专属命名空间 %d 处（首个：%s）—— %s"
                       % (len(refs), refs[0][1], note))
        if dist == DIST_SERVER and section != "client" and soft_refs:
            out.append("[提示] 其中 %d 处落在 net.neoforged.neoforge.client.**：该命名空间在"
                       " neoforge-*-universal.jar（专服实际分发的那份）里**仍然存在**，"
                       "故本校验器不判负，仅提示" % len(soft_refs))
        if dist == DIST_SERVER and section == "unregistered":
            out.append("[提示] 该文件没有登记在 mixins.json 的任何段里 ⇒ 运行期不会作为 Mixin 应用，"
                       "请确认是有意为之")

        # ---- client 段的 Mixin 在专服上根本不加载：跳过（避免误报）----
        if dist == DIST_SERVER and section == "client":
            stats["skipped_client"] += 1
            out.append("[跳过] 该 Mixin 属于 mixins.json 的 client 段 ⇒ 专用服务端不加载它，"
                       "不做 @Shadow / 注入目标断言")
            for line in out:
                print("    " + line)
            total += issues
            if issues:
                problem_files.append("%s (%d)" % (rel, issues))
            continue

        for shadow in shadows:
            if shadow.kind == "field":
                stats["shadow_field"] += 1
            else:
                stats["shadow_method"] += 1
            issues += check_shadow(shadow, src.target, index, out)
        for inj in injections:
            stats["inject"] += 1
            issues += check_injection(inj, src.target, index, out)
            if inj.annotation == "Redirect":
                stats["redirect"] += 1
                if (inj.at_value or "").upper() in REDIRECT_CHECKED_MODES:
                    stats["redirect_checked"] += 1
                issues += check_redirect_handler(inj, src.target, index, out)
        for line in out:
            print("    " + line)
        if not shadows and not injections:
            print("    （无 @Shadow / 注入注解）")
        total += issues
        if issues:
            problem_files.append("%s (%d)" % (rel, issues))

    return total, stats, problem_files


def main():
    global MIXIN_DIR, MIXINS_JSON
    mixin_dir_opt = _cli_option("--mixin-dir", None)
    if mixin_dir_opt:
        MIXIN_DIR = os.path.abspath(mixin_dir_opt)
    json_opt = _cli_option("--mixins-json", None)
    if json_opt:
        MIXINS_JSON = os.path.abspath(json_opt)
    label = _cli_option("--label", "")

    if not os.path.isfile(JAVAP):
        print("[FATAL] 找不到 javap：%s" % JAVAP)
        sys.exit(2)

    cache, cache_source = gc.cache_root()
    mc_jar, mc_notes = find_mc_jar(cache)
    rs_jar, rs_notes = find_rs_jar(cache)
    create_jar, create_notes = find_create_jar(cache)
    classpath = mc_jar + os.pathsep + rs_jar + os.pathsep + create_jar

    sections, section_notes = load_mixin_sections(MIXINS_JSON)

    files = sorted(glob.glob(os.path.join(MIXIN_DIR, "**", "*.java"), recursive=True))
    print("=" * 100)
    print("Mixin @Shadow / 注入目标 校验器 —— 两遍：客户端（CLIENT）+ 专用服务端（DEDICATED_SERVER）")
    if label:
        print("  本次标题   : %s" % label)
    print("  Mixin 目录 : %s" % MIXIN_DIR)
    print("  Gradle 缓存: %s  [%s]" % (cache, cache_source))
    for coord, (version, why) in sorted(gc.pinned_versions().items()):
        print("  pin        : %s = %s  (%s)" % (coord, version, why))
    # 选版说明（多版本并存 / pin 缺失告警）全部打印，绝不静默 —— 结论只对这里列出的 jar 成立。
    for line in mc_notes + rs_notes + create_notes:
        print("  " + line.strip())
    for line in section_notes:
        print(line)
    print("  MC  jar    : %s" % mc_jar)
    print("  RS  jar    : %s" % rs_jar)
    print("  Create jar : %s" % create_jar)
    print("  javap      : %s" % JAVAP)
    print("  服务端 dist 视界（这些命名空间在 DEDICATED_SERVER 上视为不存在，硬判据）:")
    for prefix in CLIENT_ONLY_PREFIXES:
        print("      %s**" % prefix)
    print("  dist 判据的制品级证据（可复算）:")
    for line in dist_evidence_lines(cache, mc_jar):
        print(line)
    print("=" * 100)

    # 两遍各用**独立**的索引：客户端一遍能看见客户端类，服务端一遍看不见。
    client_index = JarIndex(classpath, dist=DIST_CLIENT)
    server_index = JarIndex(classpath, dist=DIST_SERVER)

    client_total, client_stats, client_problems = run_pass(
        "第 1 遍：客户端语境（CLIENT）", DIST_CLIENT, files, sections, client_index)
    server_total, server_stats, server_problems = run_pass(
        "第 2 遍：专用服务端语境（DEDICATED_SERVER）", DIST_SERVER, files, sections, server_index)

    # --- 服务端一遍「抹掉了哪些客户端专属类」的自证 ---
    print("")
    print("=" * 100)
    print("服务端 dist 视界自证：本遍被判定为「专服上不存在」的类共 %d 个"
          % len(server_index.dist_hidden))
    for name, why in server_index.dist_hidden[:20]:
        print("    - %s  （%s）" % (name, why))
    if len(server_index.dist_hidden) > 20:
        print("    … 另有 %d 个" % (len(server_index.dist_hidden) - 20))
    if not server_index.dist_hidden:
        print("    （本次没有任何 mixin 目标落在客户端专属命名空间 —— 这对 common 段是好事）")

    print("")
    print("=" * 100)
    print("【第 1 遍 CLIENT】检查 Mixin 文件 %d，@Shadow 字段 %d，@Shadow 方法/@Accessor %d，"
          "注入目标 %d，@Redirect 深校验 %d，问题 %d"
          % (client_stats["files"], client_stats["shadow_field"], client_stats["shadow_method"],
             client_stats["inject"], client_stats["redirect_checked"], client_total))
    print("【第 2 遍 DEDICATED_SERVER】检查 Mixin 文件 %d（其中跳过 client 段 %d 个），"
          "@Shadow 字段 %d，@Shadow 方法/@Accessor %d，注入目标 %d，@Redirect 深校验 %d，问题 %d"
          % (server_stats["files"], server_stats["skipped_client"], server_stats["shadow_field"],
             server_stats["shadow_method"], server_stats["inject"],
             server_stats["redirect_checked"], server_total))
    if client_problems:
        print("有问题的文件（客户端一遍）  : %s" % ", ".join(client_problems))
    if server_problems:
        print("有问题的文件（服务端一遍）  : %s" % ", ".join(server_problems))

    # 防「假绿」：有 @Redirect 却一处都没深入校验到，说明 @At(value/target) 解析又退化了。
    for pass_name, stats in (("客户端一遍", client_stats), ("服务端一遍", server_stats)):
        if stats["redirect"] and not stats["redirect_checked"]:
            print("[FATAL] %s：存在 @Redirect 但没有任何一处通过 handler 签名校验 —— "
                  "@At(value/target) 解析可能失效，结论不可信" % pass_name)
            sys.exit(2)

    total_issues = client_total + server_total
    print("问题总数（两遍各自计数之和）: %d  （客户端 %d + 服务端 %d）"
          % (total_issues, client_total, server_total))
    if total_issues:
        print("退出码                     : 1")
        sys.exit(1)
    print("退出码                     : 0")
    sys.exit(0)


if __name__ == "__main__":
    main()
