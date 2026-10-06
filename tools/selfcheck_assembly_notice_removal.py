# -*- coding: utf-8 -*-
"""④ 「过程性播报已删除」双向断言（语言键 + 调用点）。

用法：python tools/selfcheck_assembly_notice_removal.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

用户原话：「我拨下线缆之后，它提示断了」「我点击继续，它能够正常重新开始计时」「然后再次提示不行」
「你先这些细，也就是我点击继续、检测器重新计时，这些话语不要有」。

判定标准（为什么删、为什么留）：
  * **删**：文案描述的是「玩家自己刚刚做的动作」—— 断开 / 恢复线缆、点「继续」。
    动作的结果在世界上本来就看得到（连接臂变了 / 挂起标记消失了），动作栏再复述一遍纯属噪声，
    而且会把真正必要的一次性状态信息淹掉。
  * **留**：能解释「为什么不动 / 为什么点了没反应」的一次性信息 ——
    挂起横幅（「由于 <步骤> 的执行器掉线」「由于 <原料> 缺少或自动合成失败」）、
    监视器上的挂起标签与 tooltip、真实失败提示（任务已不存在 / 执行器不可达）、
    更换机器成功提示（一次性结果反馈，不是过程复述）。

本脚本是**只读**断言版（真正改语言文件的工具是 tools/remove_notice_lang.py，用 python 改而不是手改 JSON）。
"""
import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
JAVA_ROOT = os.path.join(ROOT, "src", "main", "java")

FAILURES = []
CHECKS = [0]

# 删除清单（用户点名不要的过程性播报）：键 + 人话说明。
DELETED = {
    "message.rs_create_compat.assembly.resumed": "点「继续」后的「已继续该序列装配任务（检测器重新计时）」",
    "message.rs_create_compat.cable_seam_cut": "拨下线缆后的「已断开：此处不再连接」",
    "message.rs_create_compat.cable_seam_restored": "「已恢复：此处重新连接」",
    "message.rs_create_compat.cable_seam_none": "「这里没有可断开的连接」",
    "message.rs_create_compat.cable_face_cut": "「该面已停止自动连接」",
    "message.rs_create_compat.cable_face_restored": "「该面已恢复自动连接」",
}

# 保留清单（一次性状态 / 真实失败 / 监视器静态标签）。
KEPT = {
    "gui.rs_create_compat.assembly.banner.suspended": "挂起横幅标题（一次性）",
    "gui.rs_create_compat.assembly.banner.offline_prefix": "「由于 … 的执行器掉线」（解释为什么不动）",
    "gui.rs_create_compat.assembly.banner.material_prefix": "「由于 … 缺少或自动合成失败」",
    "gui.rs_create_compat.assembly.monitor.resume": "监视器「继续」按钮标签（静态标签，不是播报）",
    "gui.rs_create_compat.assembly.monitor.suspended.offline": "监视器挂起标记「已挂起：执行器离线」",
    "message.rs_create_compat.assembly.action_failed": "真实失败（任务已不存在 / 执行器不可达）",
    "message.rs_create_compat.assembly.machine_changed": "更换机器的一次性结果反馈",
}


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


def load(name):
    with io.open(os.path.join(LANG_DIR, name), encoding="utf-8") as handle:
        return json.load(handle)


def read(path):
    with io.open(path, encoding="utf-8") as handle:
        return handle.read()


def java_files():
    for root, _dirs, files in os.walk(JAVA_ROOT):
        for name in sorted(files):
            if name.endswith(".java"):
                yield os.path.join(root, name)


zh = load("zh_cn.json")
en = load("en_us.json")

# ==================== 1. 语言键侧：删除清单两文件都不存在 ====================
section("1) 语言键侧：删除清单在两份语言文件里都不存在")

for key, why in sorted(DELETED.items()):
    check("已删除：%s（%s）" % (key, why), key not in zh and key not in en)

# ==================== 2. 调用点侧：全仓 .java 不再引用这些键 ====================
section("2) 调用点侧：全仓 .java 不再出现这些键的字面量（键与调用点一并删除）")

hits = []
for path in java_files():
    text = read(path)
    for key in DELETED:
        if key in text:
            hits.append("%s -> %s" % (os.path.relpath(path, ROOT).replace("\\", "/"), key))
check("没有任何 .java 再引用被删的键", not hits, "; ".join(hits))

wrench = read(os.path.join(JAVA_ROOT, "cretae", "cookiewyq", "rs_create_compat", "support",
                           "RsccWrenchCableInteraction.java"))
action = read(os.path.join(JAVA_ROOT, "cretae", "cookiewyq", "rs_create_compat", "network",
                           "AssemblyTaskActionPacket.java"))
check("拨线缆的交互类里已无 actionbar 播报（displayClientMessage 一处不剩），"
      "但状态切换本身仍在（toggleSeam / toggleFace 都还在）",
      "displayClientMessage" not in wrench
      and "RsccCableCuts.toggleSeam(level, pos, seam);" in wrench
      and "RsccCableCuts.toggleFace(level, pos, face);" in wrench)
check("「继续」动作包只在**失败**时提示一次；成功时一个字都不播（用户点名的那句已删）",
      "displayClientMessage" in action
      and action.count("displayClientMessage") == 1
      and "message.rs_create_compat.assembly.action_failed" in action
      and "assembly.resumed" not in action
      and "if (!AssemblyWatchdog.resume(player, packet.taskId())) {" in action)
check("成就（一刀两断 / 破镜重圆）不受影响：触发点在 RsccCableCuts 内部，"
      "因此删掉动作栏文案不会连带删掉成就",
      "RsccAdvancements.onSeamToggled(level, pos, wasCut);" in read(
          os.path.join(JAVA_ROOT, "cretae", "cookiewyq", "rs_create_compat", "support",
                       "RsccCableCuts.java")))

# ==================== 3. 保留清单仍完整（避免「删过头」） ====================
section("3) 保留清单：一次性状态 / 真实失败 / 监视器静态标签都还在")

for key, why in sorted(KEPT.items()):
    check("保留：%s（%s）" % (key, why), key in zh and key in en)

# ==================== 4. 一致性：中英成对、中文单条 ≤ 40 字 ====================
section("4) 语言文件一致性（键成对、中文单条 ≤ 40 字）")

only_zh = sorted(set(zh) - set(en))
only_en = sorted(set(en) - set(zh))
check("中英键集合完全一致（删除是成对删除）", not only_zh and not only_en,
      "only_zh=%s only_en=%s" % (only_zh, only_en))
too_long = sorted(key for key in KEPT if len(str(zh.get(key, ""))) > 40)
check("保留清单里的每一条中文都 ≤ 40 字（本轮不动任何既有长文案，例如 `/… usage` 这类指令帮助）",
      not too_long, str(too_long))

# ==================== 结果 ====================
print()
print("=" * 78)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
