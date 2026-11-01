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


def check(name, ok, detail="", hard=True):
    CHECKS[0] += 1
    if not ok and not hard:
        print("INFO %s%s" % (name, (" | " + detail) if detail else ""))
        return ok
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

    <p><b>前提失效时返回 None（2026-10-17 修）</b>：本脚本的 ②③ 两组的**全部说服力**都来自
    「HEAD 版（= 1.1.0 线上那一版）在这些反例上报 0 问题、放行」。一旦本轮的修复已经**提交**，
    HEAD 与工作区就是<b>同一份文件</b>，那个前提自动消失 —— 此时旧检查同样会判负，
    ②③ 的「0 问题」断言必然失败，让人误以为「本轮改动破坏了检查」，而真实原因只是
    基线随提交一起前移（实证：两者 md5 完全相同 `05f62b1fc96e32be38b5a6b05b260db9`，
    且失败输出正是新规则的 `[专服 dist 违规]`）。因此这里显式比对两份文件的哈希：
    只有它们在**修复前后确实不同**时，才把它当作「旧检查」加载。
    """
    src = subprocess.run(["git", "show", "HEAD:tools/verify_mixin_shadows.py"],
                         cwd=ROOT, capture_output=True, text=True,
                         encoding="utf-8", errors="replace")
    if src.returncode != 0 or not src.stdout.strip():
        return None
    head_text = src.stdout
    try:
        with io.open(CHECKER, "r", encoding="utf-8") as handle:
            work_text = handle.read()
    except OSError:
        work_text = ""
    if work_text and work_text == head_text:
        print("[info] HEAD 版的 tools/verify_mixin_shadows.py 与工作区**逐字节相同**"
              "（修复已提交 ⇒ 基线前移）⇒ 「旧检查放行」这一反例无法复现，"
              "②③ 的旧检查断言按前提失效跳过；新检查的两遍断言照常执行。")
        return None
    # 判据 2（2026-10-24 补）：**直接问 HEAD 版里有没有新规则**，而不是靠两份文件是否逐字节相同。
    # 为什么不靠哈希：只要工作区对校验器做了任何注释级 / 健壮性级改动（例如给 javap 加
    # kill+重试，见 verify_mixin_shadows.py 的 run_javap），两份文件就不再相同，但 HEAD 里
    # 的新规则**仍然存在** —— 此时把 HEAD 当「1.1.0 的旧检查」用是错的：它一样会判负，
    # ②③ 的「旧检查 0 问题」断言必然失败，看上去像「本轮改动破坏了检查」。
    # 实证：本轮 HEAD 已含 `DIST_SERVER`（提交 5a25047「服务端校验一遍」），旧检查在
    # fixture A/B 上同样退出码 1。真正的前提是「HEAD 还没有新规则」。
    if "DIST_SERVER" in head_text or "专服 dist 违规" in head_text:
        print("[info] HEAD 版的校验器**已经包含**「专用服务端 dist」规则（修复已提交 ⇒ 基线前移）"
              "⇒ 「旧检查放行」这一反例在 HEAD 上无法复现，②③ 的旧检查断言按前提失效跳过；"
              "新检查的两遍断言（对照 / fixture A / fixture B）照常执行。")
        return None
    os.makedirs(WORK, exist_ok=True)
    head_path = os.path.join(WORK, "head_checker.py")
    with open(head_path, "w", encoding="utf-8") as handle:
        handle.write(head_text)
    # _gradle_cache 在 tools/ 下；HEAD 版会 sys.path.insert(自己的目录)，这里先补上。
    if TOOLS not in sys.path:
        sys.path.insert(0, TOOLS)
    spec = importlib.util.spec_from_file_location("rscc_head_checker", head_path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run_head_checker(module, mixin_dir, json_path):
    """跑 HEAD 版校验器（把 MIXIN_DIR / MIXINS_JSON 都指到 fixture），返回 (退出码, 输出)。

    <p><b>为什么必须同时指 json（2026-10-24 修）</b>：HEAD 版是用模块内直接调 `main()` 的方式跑的
    （因为 `--mixin-dir` 之类参数走不到），而 `main()` 的 `--mixins-json` 默认值指向**本仓库真实的**
    `rs_create_compat.mixins.json`；新校验器却是以子进程 + `--mixin-dir/--mixins-json` 跑的。
    只改 `MIXIN_DIR` 会让两边**输入不同**（旧检查器读真实 json、新检查器读 fixture json），
    于是「旧检查器 0 问题」这条对照断言测的不再是「旧规则放行」，而是「两个不同的输入谁报错」——
    一旦有人先改了校验器（工作区与 HEAD 不同），这条断言就会翻车（实测：第 354 轮把 javap
    改成 kill+重试后，②③ 的旧检查断言全部 FAIL，原因就是旧检查器拿真实 json 去比 fixture 文件，
    报「登记了但文件不存在」）。这里把两边输入对齐：都只看 fixture 的 mixin 目录与 fixture json。
    """
    module.MIXIN_DIR = mixin_dir
    module.MIXINS_JSON = json_path
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
    # ⓪ 是**前提**断言而非判据断言：HEAD 与工作区逐字节相同时（修复已提交 ⇒ 基线前移），
    # 「旧检查放行」这一反例在定义上就无法复现，此时按 INFO 跳过，不计入失败
    # —— 新检查的判据断言（① 对照 / ② A / ③ B）仍然全量执行，规则本身照样被证伪过。
    check("⓪ 能拿到与工作区**不同**的 git HEAD 版旧校验器（1.1.0 发布时线上那一版）",
          head is not None,
          "HEAD == 工作区（修复已提交）⇒ 无可复现的「旧检查放行」基线；与旧检查相关的断言跳过",
          hard=False)

    # ---------------- ① 对照：段位与真实工程一致 ⇒ 必须 0 问题（不误报） ----------------
    c_dir, c_json = build_fixture("control", {SLOT_REL: "mixins", CLIENT_REL: "client"})
    rc, out = run_new_checker(c_dir, c_json, "round65 对照")
    check("① 对照：新校验器退出码 0", rc == 0, "实际 %s" % rc)
    check("① 对照：客户端一遍 0 问题", pass_problems(out, "client") == 0,
          "实际 %s" % pass_problems(out, "client"))
    check("① 对照：服务端一遍 0 问题（client 段的 Mixin 被跳过，不误报）",
          pass_problems(out, "server") == 0, "实际 %s" % pass_problems(out, "server"))
    if head:
        rc_c_old, _ = run_head_checker(head, c_dir, c_json)
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
        rc_a_old, _ = run_head_checker(head, a_dir, a_json)
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
        rc_b_old, _ = run_head_checker(head, b_dir, b_json)
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
