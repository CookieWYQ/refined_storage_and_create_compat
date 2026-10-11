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

用法
----
    python tools/verify_mixin_shadows.py

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
    """按类名查真实成员；结果缓存，避免重复调用 javap。"""

    def __init__(self, classpath):
        self.classpath = classpath
        self._cache = {}
        self._failed = {}
        self._warned = set()

    def get(self, bin_name):
        if bin_name in self._cache:
            return self._cache[bin_name]
        if bin_name in self._failed:
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
                 handler_static=False):
        self.annotation = annotation
        self.name = name
        self.desc = desc          # None 表示源码只写了方法名
        self.raw = raw
        self.at = at
        self.cancellable = cancellable
        self.require = require
        self.expect = expect
        self.handler_static = handler_static


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
        if kw.get("at"):
            at_raw = kw["at"].strip()
            if at_raw.startswith("@At"):
                at_raw = at_raw[3:].strip()
            if at_raw.startswith("(") and at_raw.endswith(")"):
                at_raw = at_raw[1:-1]
            at_pos, _at_kw = split_args(at_raw)
            at_name = join_string_literals(at_pos[0]) if at_pos else None
        elif pos:
            at_name = join_string_literals(pos[0])
        injections.append(InjectionPoint(ann_name, mname, mdesc, decl, at_name,
                                         kw.get("cancellable"), kw.get("require"),
                                         kw.get("expect"), handler_static))

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


def main():
    if not os.path.isfile(JAVAP):
        print("[FATAL] 找不到 javap：%s" % JAVAP)
        sys.exit(2)

    cache, cache_source = gc.cache_root()
    mc_jar, mc_notes = find_mc_jar(cache)
    rs_jar, rs_notes = find_rs_jar(cache)
    create_jar, create_notes = find_create_jar(cache)
    classpath = mc_jar + os.pathsep + rs_jar + os.pathsep + create_jar
    index = JarIndex(classpath)

    files = sorted(glob.glob(os.path.join(MIXIN_DIR, "**", "*.java"), recursive=True))
    print("=" * 100)
    print("Mixin @Shadow / 注入目标 校验器")
    print("  Mixin 目录 : %s" % MIXIN_DIR)
    print("  Gradle 缓存: %s  [%s]" % (cache, cache_source))
    for coord, (version, why) in sorted(gc.pinned_versions().items()):
        print("  pin        : %s = %s  (%s)" % (coord, version, why))
    # 选版说明（多版本并存 / pin 缺失告警）全部打印，绝不静默 —— 结论只对这里列出的 jar 成立。
    for line in mc_notes + rs_notes + create_notes:
        print("  " + line.strip())
    print("  MC  jar    : %s" % mc_jar)
    print("  RS  jar    : %s" % rs_jar)
    print("  Create jar : %s" % create_jar)
    print("  javap      : %s" % JAVAP)
    print("=" * 100)

    total_issues = 0
    n_shadow_field = n_shadow_method = n_inject = 0
    problem_files = []

    for path in files:
        src, shadows, injections = parse_source(path)
        print("")
        print("--- %s" % src.rel_name())
        if not src.target:
            print("    目标类 : <未解析到 @Mixin(Target.class)%s>"
                  % ("，kind=" + src.mixin_kind if src.mixin_kind else ""))
            continue
        print("    目标类 : %s" % src.target)

        out = []
        issues = 0
        for shadow in shadows:
            if shadow.kind == "field":
                n_shadow_field += 1
            else:
                n_shadow_method += 1
            issues += check_shadow(shadow, src.target, index, out)
        for inj in injections:
            n_inject += 1
            issues += check_injection(inj, src.target, index, out)
        for line in out:
            print("    " + line)
        if not shadows and not injections:
            print("    （无 @Shadow / 注入注解）")
        total_issues += issues
        if issues:
            problem_files.append("%s (%d)" % (src.rel_name(), issues))

    print("")
    print("=" * 100)
    print("检查 Mixin 文件            : %d" % len(files))
    print("@Shadow 字段               : %d" % n_shadow_field)
    print("@Shadow 方法 / @Accessor   : %d" % n_shadow_method)
    print("@Inject/@Redirect/@Overwrite 目标 : %d" % n_inject)
    print("问题总数                   : %d" % total_issues)
    if problem_files:
        print("有问题的文件               : %s" % ", ".join(problem_files))
        print("退出码                     : 1")
        sys.exit(1)
    print("退出码                     : 0")
    sys.exit(0)


if __name__ == "__main__":
    main()
