#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 65 轮：**专用服务端（DEDICATED_SERVER）这一遍**的反例自证。

为什么必须有这个脚本
--------------------
1.1.0 让专用服务端「启动即崩」，而**当时所有的静态校验（git HEAD 里的
tools/verify_mixin_shadows.py）在同一份坏代码上报 0 问题、退出码 0**。
如果只写新规则、不留下「旧检查放行 / 新检查判负」的**可复跑**证据，下一个人就无法判断
这条规则究竟有没有用（本工程已经吃过一次「报 0 问题却实机崩」的亏）。

本脚本在 build/round65_fixture 下造**最小化** fixture（只放参与断言的源文件 +
一份最小 mixins.json），**绝不改任何业务源码**，做四组断言：

  ① 对照 fixture（两个文件，段位与真实工程一致）
     ⇒ 新检查两遍都 0 问题、退出码 0（证明新规则不误报）；
  ② fixture A：把 `client.AbstractBaseScreenMixin` 放进 **common 段**
     （= 「客户端 Mixin 被登记进专服会加载的段」，正是 2026-09 崩过的坑）
     ⇒ 期望：**旧检查 0 问题（放行）**、新检查「客户端一遍」0 问题、
        「服务端一遍」判负（[专服 dist 违规]）、退出码 1；
  ③ fixture B：把 AutocrafterManagerSlotMixin 的 NEW 工厂 handler 返回类型改回 1.1.0 的
     `Slot`（`@At` 目标仍是 `PatternSlot`）
     ⇒ 期望：**旧检查 0 问题（放行）**、新检查判负（[签名不符]）、退出码 1；
  ④ 前提自证：上面说的「旧检查」确实是 `git show HEAD:tools/verify_mixin_shadows.py`
     （= 1.1.0 发布时线上那一版），且在 ②③ 的同一份 fixture 上确实报 0 问题。

用法
    python tools/selfcheck_round65_dedicated_server_pass.py
退出码：0 = 全部符合预期；1 = 有断言失败。
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import os
import re
import shutil
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(ROOT, "tools")
CHECKER = os.path.join(TOOLS, "verify_mixin_shadows.py")
REAL_MIXIN_DIR = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq",
                              "rs_create_compat", "mixin")
WORK = os.path.join(ROOT, "build", "round65_fixture")

# 参与断言的两个真实 Mixin（相对 mixin 包根的路径）
SLOT_REL = "AutocrafterManagerSlotMixin"                 # 顶层，包 cretae...mixin
CLIENT_REL = "client/AbstractBaseScreenMixin"            # 包 cretae...mixin.client

FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))
    if not ok:
        FAILURES.append("%s %s" % (name, detail))
    return ok


def json_literal(sections):
    """生成最小 mixins.json；sections = {相对类名（点分）: 'mixins'|'client'}。"""
    common = [e for e, s in sections.items() if s == "mixins"]
    client = [e for e, s in sections.items() if s == "client"]
    return (
        "{\n"
        "  \"required\": true,\n"
        "  \"minVersion\": \"0.8\",\n"
        "  \"package\": \"cretae.cookiewyq.rs_create_compat.mixin\",\n"
        "  \"compatibilityLevel\": \"JAVA_21\",\n"
        "  \"mixins\": [%s],\n"
        "  \"client\": [%s],\n"
        "  \"injectors\": { \"defaultRequire\": 1 }\n"
        "}\n"
        % (", ".join('"%s"' % e for e in common), ", ".join('"%s"' % e for e in client))
    )


def build_fixture(case, sections):
    """造一个**最小化** fixture：只放参与断言的 mixin 源文件 + 一份最小 mixins.json。

    为什么不用「整份 35 个文件复制一遍」：校验器要对每个 `@Mixin` 目标类做继承链的 javap
    （几十次子进程），35 个文件 × 两遍 × 6 次运行要二十多分钟；最小化后每次只剩 1~2 个文件，
    秒级完成，而断言的语义完全一样（规则只看单个 Mixin 的源码 + 它在 mixins.json 的哪一段）。
    """
    base = os.path.join(WORK, case)
    if os.path.isdir(base):
        shutil.rmtree(base, ignore_errors=True)
    mixin_dir = os.path.join(base, "mixin")
    os.makedirs(mixin_dir)
    for rel in sections:
        src = os.path.join(REAL_MIXIN_DIR, rel.replace("/", os.sep) + ".java")
        dst = os.path.join(mixin_dir, rel.replace("/", os.sep) + ".java")
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)
    json_path = os.path.join(base, "rs_create_compat.mixins.json")
    with open(json_path, "w", encoding="utf-8") as handle:
        handle.write(json_literal(sections))
    return mixin_dir, json_path


def run_new_checker(mixin_dir, json_path, label):
    """跑「新」校验器（当前工作区的 tools/verify_mixin_shadows.py），返回 (退出码, 输出)。"""
    proc = subprocess.run(
        [sys.executable, CHECKER,
         "--mixin-dir", mixin_dir, "--mixins-json", json_path, "--label", label],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=1800)
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def load_head_checker():
    """把 git HEAD 版的校验器导出到 build/ 下并作为模块加载（不改工作区文件）。

    返回模块对象；拿不到（没有 git / 没有 HEAD 版本）时返回 None。
    """
    src = subprocess.run(["git", "show", "HEAD:tools/verify_mixin_shadows.py"],
                         cwd=ROOT, capture_output=True, text=True,
                         encoding="utf-8", errors="replace")
    if src.returncode != 0 or not src.stdout.strip():
        return None
    os.makedirs(WORK, exist_ok=True)
    head_path = os.path.join(WORK, "head_checker.py")
    with open(head_path, "w", encoding="utf-8") as handle:
        handle.write(src.stdout)
    # _gradle_cache 在 tools/ 下；HEAD 版会 sys.path.insert(自己的目录)，这里先补上。
    if TOOLS not in sys.path:
        sys.path.insert(0, TOOLS)
    spec = importlib.util.spec_from_file_location("rscc_head_checker", head_path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run_head_checker(module, mixin_dir):
    """跑 HEAD 版校验器（把 MIXIN_DIR 指到 fixture），返回 (退出码, 输出)。"""
    module.MIXIN_DIR = mixin_dir
    buf = io.StringIO()
    code = 0
    old_argv = sys.argv
    sys.argv = ["head_checker"]
    try:
        with contextlib.redirect_stdout(buf):
            module.main()
    except SystemExit as exc:
        code = exc.code if isinstance(exc.code, int) else 1
    except Exception as exc:  # pragma: no cover
        code = -1
        buf.write("\n[旧检查器抛异常] %r" % (exc,))
    finally:
        sys.argv = old_argv
    return code, buf.getvalue()


def pass_problems(out, which):
    """从新校验器的输出里抠出某一遍的问题数；抠不到返回 None。"""
    if which == "client":
        m = re.search(r"【第 1 遍 CLIENT】[^\n]*?问题 (\d+)", out)
    else:
        m = re.search(r"【第 2 遍 DEDICATED_SERVER】[^\n]*?问题 (\d+)", out)
    return int(m.group(1)) if m else None


def excerpt(text, needle, width=200):
    pos = text.find(needle)
    return text[pos:pos + width] if pos >= 0 else "<未出现 %s>" % needle


def main():
    print("=" * 100)
    print("第 65 轮反例自证：专用服务端（DEDICATED_SERVER）这一遍")
    print("  新校验器 : %s" % CHECKER)
    print("  工作目录 : %s（最小化临时副本，不动 src/）" % WORK)
    print("=" * 100)

    head = load_head_checker()
    check("⓪ 能拿到 git HEAD 版的旧校验器（1.1.0 发布时线上那一版）", head is not None,
          "拿不到就无法证明「旧检查放行」；与旧检查相关的断言会跳过")

    # ---------------- ① 对照：段位与真实工程一致 ⇒ 必须 0 问题（不误报） ----------------
    c_dir, c_json = build_fixture("control", {SLOT_REL: "mixins", CLIENT_REL: "client"})
    rc, out = run_new_checker(c_dir, c_json, "round65 对照")
    check("① 对照：新校验器退出码 0", rc == 0, "实际 %s" % rc)
    check("① 对照：客户端一遍 0 问题", pass_problems(out, "client") == 0,
          "实际 %s" % pass_problems(out, "client"))
    check("① 对照：服务端一遍 0 问题（client 段的 Mixin 被跳过，不误报）",
          pass_problems(out, "server") == 0, "实际 %s" % pass_problems(out, "server"))
    if head:
        rc_c_old, _ = run_head_checker(head, c_dir)
        check("① 对照：旧校验器同样 0 问题（两边基线一致）", rc_c_old == 0,
              "旧校验器退出码 %s" % rc_c_old)

    # ---------------- ② fixture A：客户端 Mixin 被放进 common 段 ----------------
    a_dir, a_json = build_fixture("case_a_client_mixin_in_common",
                                  {SLOT_REL: "mixins", CLIENT_REL: "mixins"})
    rc_a, out_a = run_new_checker(a_dir, a_json, "round65 A：客户端 Mixin 进了 common 段")
    check("② fixture A：新校验器退出码 1（判负）", rc_a == 1, "实际 %s" % rc_a)
    check("② fixture A：**客户端一遍 0 问题**（= 旧行为，看不见这个问题）",
          pass_problems(out_a, "client") == 0, "实际 %s" % pass_problems(out_a, "client"))
    check("② fixture A：**服务端一遍 ≥1 问题**（新规则抓到）",
          (pass_problems(out_a, "server") or 0) >= 1,
          "实际 %s" % pass_problems(out_a, "server"))
    check("② fixture A：输出里有「[专服 dist 违规]」且点名了 net.minecraft.client.**",
          "[专服 dist 违规]" in out_a and "net.minecraft.client." in out_a,
          excerpt(out_a, "[专服 dist 违规]"))
    if head:
        rc_a_old, _ = run_head_checker(head, a_dir)
        check("② fixture A：**旧校验器 0 问题、退出码 0（放行）** —— 这就是 1.1.0 的假绿",
              rc_a_old == 0, "旧校验器退出码 %s" % rc_a_old)

    # ---------------- ③ fixture B：NEW 工厂 handler 返回类型退回 Slot ----------------
    b_dir, b_json = build_fixture("case_b_slot_vs_patternslot", {SLOT_REL: "mixins"})
    slot_file = os.path.join(b_dir, "AutocrafterManagerSlotMixin.java")
    with open(slot_file, encoding="utf-8") as handle:
        src = handle.read()
    broken = src.replace(
        "private static PatternSlot rscc$patternOnlyManagerServerSlot",
        "private static Slot rscc$patternOnlyManagerServerSlot")
    broken = broken.replace(
        "import com.refinedmods.refinedstorage.common.autocrafting.PatternSlot;",
        "import com.refinedmods.refinedstorage.common.autocrafting.PatternSlot;\n"
        "import net.minecraft.world.inventory.Slot;", 1)
    with open(slot_file, "w", encoding="utf-8") as handle:
        handle.write(broken)
    check("③ fixture B：已把 handler 返回类型改回 1.1.0 的 Slot（@At 目标仍是 PatternSlot）",
          broken != src and "private static Slot rscc$patternOnlyManagerServerSlot" in broken)

    rc_b, out_b = run_new_checker(b_dir, b_json, "round65 B：Slot vs PatternSlot")
    check("③ fixture B：新校验器退出码 1（判负）", rc_b == 1, "实际 %s" % rc_b)
    check("③ fixture B：输出里有「[签名不符]」且同时出现 Slot 与 PatternSlot",
          "[签名不符]" in out_b and "Slot" in out_b and "PatternSlot" in out_b,
          excerpt(out_b, "[签名不符]"))
    if head:
        rc_b_old, _ = run_head_checker(head, b_dir)
        check("③ fixture B：**旧校验器 0 问题、退出码 0（放行）** —— 发布 1.1.0 时就是这一版",
              rc_b_old == 0, "旧校验器退出码 %s" % rc_b_old)

    print("")
    if FAILURES:
        print("[FAIL] 共 %d 项断言失败（检查 %d 项）：" % (len(FAILURES), CHECKS[0]))
        for item in FAILURES:
            print("   - %s" % item)
        return 1
    print("[OK] 反例自证通过：%d 项断言全部符合预期 —— "
          "旧检查放行，新检查（服务端 dist 一遍 / @Redirect 签名）判负，且对照不误报" % CHECKS[0])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
