#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 47 轮：**专用服务端安全性** —— 注册期 / 载荷类不得触碰「客户端专属类型」。

用户实测：把模组装到专用服务端上，启动即崩，根因是
    net.minecraft.client.gui.components.toasts.Toast 在服务端不存在
    → NoClassDefFoundError → 模组加载失败。
崩溃发生在 `RegisterPayloadHandlersEvent` 分发期间（即 `RS_Create_Compat#registerPayloads` 里）。

为什么会这样（本脚本固化的判据）：
    `registrar.playToClient(X.TYPE, X.STREAM_CODEC, X::handle)` 会**读取载荷类的静态字段**，
    于是载荷类在注册期就被**初始化并链接**；JVM 链接期要校验**全部**方法字节码，而校验器在做
    「实参 ↔ 形参」的**跨类可赋值性**检查时**必须解析并加载**涉及的两个类。只要载荷类的处理体里
    出现「把客户端类型交给客户端类型形参」的写法
        Minecraft.getInstance().getToasts().addToast(new CompatCompletionToast(rows))
    专用服务端就会去加载 `CompatCompletionToast`（→ 其父接口 `Toast`）→ 服务端没有这个类 → 崩。
    （实证：用户日志报的正是 `Toast`。同一注册段里更早的 12 个包只用了 `instanceof <客户端屏幕>`
      与「静态方法调用」，JVM 校验器对这两者不加载目标类，所以它们是**同一类隐患但尚未引爆** ——
      本轮一并修掉，避免下一个包再炸一次。）

判据分层（避免误报）：
    * **外部客户端类型**：`net.minecraft.client.*`、`com.mojang.blaze3d.*`、`org.lwjgl.*`、
      `net.neoforged.neoforge.client.*`（专服 jar 里根本不存在这些类）⇒ 直接引用即高危。
    * **项目内客户端类**：本模组 `client` 包里的类**且它自己引用了外部客户端类型**
      （`client/TerminalOpenIntent` 这类「在 client 包但只用 java.lang」的纯状态类不算，见该类文档）。

断言（任一 FAIL 即退出码 1）：
    ① `registerPayloads` 方法体：不含客户端类型引用，且**被它点名的每个类都是服务端安全的**
       （方法引用 `X::handle`、lambda 里的 `X.handle(...)`、`X.TYPE` 逐一体现在这条上）；
    ② `network/**` 下不出现任何客户端类型引用（含 import，连「client 包的类」也不许出现）；
    ③ 客户端专属类型必须被正确隔离：
         * `@EventBusSubscriber` 必须声明 `value = Dist.CLIENT`；
         * mixin 包里引用客户端类型的类必须登记在 `rs_create_compat.mixins.json` 的 `client` 数组；
         * `client/` 之外的高危文件必须在 ALLOWLIST 里逐条登记并写明「为什么现在不炸」；
    ④ 客户端专属类型不得被 `new` 或用作局部变量 / 字段 / 形参的声明类型
       —— 这正是本轮崩溃的字节码形态（跨类可赋值性检查 ⇒ 类加载）。

用法：python tools/selfcheck_round47_dedicated_server_safety.py [--inventory]
退出码：0 = 没有任何 FAIL。
"""

from __future__ import annotations

import io
import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_ROOT = os.path.join(ROOT, "src", "main", "java")
PKG = "cretae.cookiewyq.rs_create_compat"
PKG_PATH = PKG.replace(".", "/")
MAIN_REL = PKG_PATH + "/RS_Create_Compat.java"
MIXIN_JSON = os.path.join(ROOT, "src", "main", "resources", "rs_create_compat.mixins.json")

EXTERNAL_CLIENT_PREFIXES = (
    "net.minecraft.client.",           # 原版客户端（gui / renderer / Minecraft / Toast）
    "com.mojang.blaze3d.",             # 渲染栈（PoseStack / RenderSystem / BufferBuilder…）
    "org.lwjgl.",                      # 窗口 / GLFW（专服没有）
    "net.neoforged.neoforge.client.",  # NeoForge 客户端事件 / 渲染扩展
)

# ---- 例外清单：`client/` 之外**允许**直接引用客户端类型（或引用客户端专属类）的文件 ----
# 每条必须写明「为什么现在不会在专服上炸」。删掉条目会让对应断言立刻失败。
ALLOWLIST = {
    "cretae/cookiewyq/rs_create_compat/item/SequenceAssemblyPatternItem.java":
        "tooltip 分层过滤（client/tooltip/RsccTooltipLayers）只在客户端被调用；物品注册早于载荷注册，"
        "用户日志已越过该阶段（校验器不解析 invokestatic / 实参同名，不加载目标类）",
    "cretae/cookiewyq/rs_create_compat/item/SequenceUnitPatternItem.java":
        "同 SequenceAssemblyPatternItem：tooltip 分层过滤只在客户端被调用",
    "cretae/cookiewyq/rs_create_compat/support/RsccImporterBarHost.java":
        "纯接口，只被 mixin/client 下的客户端 Mixin 实现；专服上没有任何类引用/加载它"
        "（GuiGraphics 只出现在方法描述符里，解析是惰性的）",
}

FAILURES = []
CHECKS = [0]


def check(name, ok, detail="", hard=True):
    CHECKS[0] += 1
    if not ok and hard:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else ("FAIL" if hard else "INFO"), name,
                       (" | " + detail) if (detail and not ok) else ""))


def strip_comments_and_strings(text):
    """把注释与字符串字面量换成空格（行号 / 列数不变），避免文档里的类名造成误报。"""
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
            if text[i:i + 3] == '"""':
                j = text.find('"""', i + 3)
                j = n if j < 0 else j + 3
            else:
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
        elif c == "'":
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "'" or text[j] == "\n":
                    j += 1
                    break
                j += 1
            out.append("".join("\n" if ch == "\n" else " " for ch in text[i:j]))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([A-Za-z0-9_.$]+)\s*;", re.M)
# 外部客户端类型 FQN：前缀 + 首字母大写的类名（可含嵌套类）；不吞掉后面的方法名
EXTERNAL_FQN_RE = re.compile(
    r"\b((?:" + "|".join(re.escape(p) for p in EXTERNAL_CLIENT_PREFIXES) + r")"
    r"[A-Z][A-Za-z0-9_$]*(?:\.[A-Z][A-Za-z0-9_$]*)*)")
# 本模组 client 包的 FQN
PROJECT_CLIENT_FQN_RE = re.compile(r"\b(" + re.escape(PKG) + r"\.client\.[A-Za-z0-9_$.]+)")


def is_external_client(fqn):
    return any(fqn.startswith(p) for p in EXTERNAL_CLIENT_PREFIXES)


def fqn_to_rel(fqn, sources):
    """项目内 FQN → 源文件相对路径（去掉嵌套类 / 字段后缀）。"""
    parts = fqn.split(".")
    for cut in range(len(parts), 0, -1):
        candidate = "/".join(parts[:cut]) + ".java"
        if candidate in sources:
            return candidate
    return None


class Source:
    def __init__(self, rel, text):
        self.rel = rel
        self.text = text
        self.code = strip_comments_and_strings(text)
        self.lines = self.code.splitlines()
        self.imports = {}         # 简单名 -> FQN
        self.external_refs = []   # (行号, FQN, 说明) 直接引用外部客户端类型
        self.client_class_refs = []  # (行号, FQN, 说明) 直接引用本模组 client 包的类
        for idx, line in enumerate(self.lines, 1):
            for m in EXTERNAL_FQN_RE.finditer(line):
                self.external_refs.append((idx, m.group(1), "内联 FQN"))
            for m in PROJECT_CLIENT_FQN_RE.finditer(line):
                self.client_class_refs.append((idx, m.group(1), "内联 FQN"))
        for m in IMPORT_RE.finditer(self.code):
            fqn = m.group(1)
            if fqn.endswith(".*"):
                continue
            simple = fqn.rsplit(".", 1)[-1]
            self.imports[simple] = fqn
            if is_external_client(fqn):
                self.external_refs.append((0, fqn, "import"))
                for idx, line in enumerate(self.lines, 1):
                    if re.search(r"\b" + re.escape(simple) + r"\b", line):
                        self.external_refs.append((idx, fqn, "简单名"))
            elif fqn.startswith(PKG + ".client."):
                self.client_class_refs.append((0, fqn, "import"))
                for idx, line in enumerate(self.lines, 1):
                    if re.search(r"\b" + re.escape(simple) + r"\b", line):
                        self.client_class_refs.append((idx, fqn, "简单名"))

    def describe(self, limit=6):
        items = []
        for line, fqn, _why in self.external_refs:
            items.append("%s:%s" % ("第%d行" % line if line else "顶部", fqn))
        for line, fqn, _why in self.client_class_refs:
            items.append("%s:%s" % ("第%d行" % line if line else "顶部", fqn))
        seen = []
        for it in items:
            if it not in seen:
                seen.append(it)
        return "; ".join(seen[:limit]) + ("" if len(seen) <= limit else " 等 %d 处" % len(seen))


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


def load_mixin_client_files():
    if not os.path.exists(MIXIN_JSON):
        return None, set()
    with io.open(MIXIN_JSON, encoding="utf-8") as handle:
        cfg = json.load(handle)
    base = cfg.get("package", "").replace(".", "/")
    return cfg, {base + "/" + entry.replace(".", "/") + ".java" for entry in cfg.get("client", [])}


def method_body(text, signature_re):
    m = re.search(signature_re, text)
    if not m:
        return None, 0
    brace = text.find("{", m.end() - 1)
    if brace < 0:
        return None, 0
    depth = 0
    i = brace
    while i < len(text):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[m.start():i + 1], text[:m.start()].count("\n") + 1
        i += 1
    return None, 0


def refs_in(fragment, src, start_line):
    """片段里的外部客户端类型 + 项目 client 类引用（行号按 start_line 平移）。"""
    hits = []
    for idx, raw in enumerate(fragment.splitlines(), start_line):
        for m in EXTERNAL_FQN_RE.finditer(raw):
            hits.append("%d:%s" % (idx, m.group(1)))
        for m in PROJECT_CLIENT_FQN_RE.finditer(raw):
            hits.append("%d:%s" % (idx, m.group(1)))
    for simple, fqn in src.imports.items():
        if not (is_external_client(fqn) or fqn.startswith(PKG + ".client.")):
            continue
        for idx, raw in enumerate(fragment.splitlines(), start_line):
            if re.search(r"\b" + re.escape(simple) + r"\b", raw):
                hits.append("%d:%s" % (idx, fqn))
    return sorted(set(hits))


def main():
    inventory = "--inventory" in sys.argv
    sources = load_sources()
    _mixin_cfg, mixin_client_files = load_mixin_client_files()
    mixin_cfg = _mixin_cfg
    client_files = {rel for rel in sources if "/client/" in "/" + rel} | mixin_client_files

    # 直接引用外部客户端类型的文件（外部污点源）
    extern = {rel for rel, src in sources.items() if src.external_refs}
    # 项目内「客户端专属类」：client 包 + 自身引用外部客户端类型
    tainted_client_classes = {rel for rel in extern if "/client/" in "/" + rel}
    # 高危文件：直接引用外部客户端类型，或引用了客户端专属类
    hazard = {}
    for rel, src in sources.items():
        if src.external_refs:
            hazard[rel] = "直接引用外部客户端类型"
            continue
        for _line, fqn, _why in src.client_class_refs:
            dep = fqn_to_rel(fqn, sources)
            if dep in tainted_client_classes:
                hazard[rel] = "引用客户端专属类 %s" % fqn
                break
    print("[info] 扫描 %d 个 Java 源文件；外部客户端类型引用 %d 个文件；高危文件 %d 个；"
          "客户端专属类 %d 个" % (len(sources), len(extern), len(hazard), len(tainted_client_classes)))

    # ---------------- ① registerPayloads ----------------
    main_src = sources.get(MAIN_REL)
    if main_src is None:
        check("① 找到 RS_Create_Compat.java", False, MAIN_REL)
    else:
        body, start_line = method_body(main_src.code, r"private\s+static\s+void\s+registerPayloads\s*\(")
        check("① 找到 registerPayloads 方法体（起始第 %d 行）" % start_line, body is not None)
        if body is not None:
            hits = refs_in(body, main_src, start_line)
            by_simple = {}
            for rel in sources:
                by_simple.setdefault(rel.rsplit("/", 1)[-1][:-5], rel)
            named = []
            for name in sorted(set(re.findall(r"\b([A-Z][A-Za-z0-9_$]*)\b", body))):
                rel = by_simple.get(name)
                if rel in hazard:
                    named.append("%s(%s)" % (name, rel))
            check("①a registerPayloads 内无客户端类型引用", not hits, "; ".join(hits[:8]))
            check("①b registerPayloads 点名的类均非客户端高危类", not named, "; ".join(named[:8]))

    # ---------------- ② network/** ----------------
    net_prefix = PKG_PATH + "/network/"
    net_files = sorted(rel for rel in sources if rel.startswith(net_prefix))
    bad_net = [rel for rel in net_files if sources[rel].external_refs or sources[rel].client_class_refs]
    for rel in bad_net:
        print("     - %s | %s" % (rel, sources[rel].describe()))
    check("② network/** 共 %d 个文件均无客户端类型引用" % len(net_files), not bad_net,
          "%d 个文件命中" % len(bad_net))

    # ---------------- ③ 隔离 ----------------
    bus_bad, bus_total = [], 0
    for rel, src in sorted(sources.items()):
        if "@EventBusSubscriber" not in src.code or rel not in hazard:
            continue
        bus_total += 1
        if not re.search(r"Dist\.CLIENT", src.code):
            bus_bad.append("%s:%s" % (rel, src.describe(3)))
    check("③a 引用客户端类型的 @EventBusSubscriber 均声明 Dist.CLIENT（%d 个）" % bus_total,
          not bus_bad, "; ".join(bus_bad))

    mixin_bad, mixin_total = [], 0
    if mixin_cfg is None:
        check("③b rs_create_compat.mixins.json 存在", False, MIXIN_JSON)
    else:
        for rel, src in sorted(sources.items()):
            if "/mixin/" not in "/" + rel or rel not in hazard:
                continue
            mixin_total += 1
            if rel not in mixin_client_files:
                mixin_bad.append("%s（未登记在 client 数组）" % rel)
        check("③b 高危 Mixin 均登记在 mixins.json 的 client 数组（%d 个）" % mixin_total,
              not mixin_bad, "; ".join(mixin_bad))

    outside = []
    for rel in sorted(hazard):
        if rel in client_files or rel in ALLOWLIST:
            continue
        outside.append("%s:%s" % (rel, sources[rel].describe(3)))
    check("③c client/ 之外无「未登记」的高危文件", not outside, "; ".join(outside))
    for rel in sorted(ALLOWLIST):
        if rel not in sources:
            check("③c 例外清单条目存在（%s）" % rel, False)
    # 补充说明（非断言）：`client` 包里**自身不引用任何客户端类型**的类（例如
    # client/TerminalOpenIntent：只用 java.lang 的纯状态位）被 common 侧引用是安全的
    # —— 专服即使真的加载它也不会缺类。若它哪天开始引用客户端类型，这里会立刻变成 ③c 的 FAIL。
    for rel in sorted(sources):
        src = sources[rel]
        if rel in hazard or rel in client_files:
            continue
        safe = sorted({fqn for _l, fqn, _w in src.client_class_refs
                       if fqn_to_rel(fqn, sources) not in tainted_client_classes})
        if safe:
            print("     INFO 安全引用（client 包内自身不含客户端类型的纯状态类）：%s -> %s"
                  % (rel, ", ".join(safe[:3])))

    # ---------------- ④ new / 声明客户端专属类型 ----------------
    decl_bad, decl_soft = [], []
    for rel in sorted(set(hazard) | {r for r in sources if sources[r].external_refs
                                     or sources[r].client_class_refs}):
        if rel in client_files:
            continue
        src = sources[rel]
        dangerous_simples = set()
        for simple, fqn in src.imports.items():
            if is_external_client(fqn):
                if rel in extern:
                    dangerous_simples.add(simple)
            elif fqn.startswith(PKG + ".client.") and fqn_to_rel(fqn, sources) in tainted_client_classes:
                dangerous_simples.add(simple)
        for simple in sorted(dangerous_simples):
            for idx, line in enumerate(src.lines, 1):
                if re.search(r"\bnew\s+[A-Za-z0-9_$.]*\b" + re.escape(simple) + r"\s*\(", line):
                    entry = "%s:%d new %s" % (rel, idx, simple)
                    (decl_soft if rel in ALLOWLIST else decl_bad).append(entry)
                if re.search(r"\b" + re.escape(simple) + r"(?:<[^;=()]*>)?\s+[a-z_][A-Za-z0-9_]*\s*[=;,)]", line):
                    entry = "%s:%d 声明 %s" % (rel, idx, simple)
                    (decl_soft if rel in ALLOWLIST else decl_bad).append(entry)
    check("④ client/ 之外未 new / 声明客户端专属类型", not decl_bad, "; ".join(decl_bad[:8]))
    for entry in decl_soft:
        print("     INFO 例外（已登记）：%s" % entry)

    if inventory:
        print("\n[inventory] 高危文件（client/ 之外）：")
        for rel in sorted(hazard):
            if rel in client_files:
                continue
            mark = "ALLOW" if rel in ALLOWLIST else "----"
            print("  %s %s | %s" % (mark, rel, hazard[rel]))
        print("[inventory] 只是「引用了 client 包类但其本身服务端安全」的文件（不算高危）：")
        for rel in sorted(sources):
            src = sources[rel]
            if rel in hazard or rel in client_files or not src.client_class_refs:
                continue
            safe = {fqn for _l, fqn, _w in src.client_class_refs
                    if fqn_to_rel(fqn, sources) not in tainted_client_classes}
            if safe:
                print("  ---- %s -> %s" % (rel, ", ".join(sorted(safe)[:4])))

    print("")
    if FAILURES:
        print("[FAIL] 共 %d 项断言失败（检查 %d 项）：" % (len(FAILURES), CHECKS[0]))
        for f in FAILURES:
            print("   - %s" % f)
        return 1
    print("[OK] 专用服务端安全性：%d 项断言全部通过" % CHECKS[0])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
