# -*- coding: utf-8 -*-
"""第 49 轮 · 任务 A：**伪装（分隔框架 / 伪装框架）可以套在外部存储总线上**（用户第 3 条）。

用户原话：「使得这一个**伪装**可以套上这个**输入输出总线**以及这个**外部存储总线**。」

本轮结论（本脚本固化的判据）：
    * 「可套壳族」= RS 线缆 ∪ RS 输入总线 ∪ RS 输出总线 ∪ **RS 外部存储总线**
      ∪ Create 流体管道族 ∪ Create 传动杆族。输入 / 输出总线本来就在里面
      （它们是 `RsccWireBlocks.isWire` 的成员），本次只补外部存储总线。
    * 「可穿行族」（`RsccWireBlocks.isWire`）**一字未动** —— 它决定「总线能不能隔着它
      够到执行舱」，把外部存储总线放进去会凭空改变延长型的归属判定，是用户没要求的语义改动。
      因此外部存储总线走的是**单独一个谓词** `RsccWireBlocks.isExternalStorageBus`。
    * 两处清单必须同时覆盖它，否则会出现「能裹上去、但那一格不显示外壳」：
      ① `SeparationFrameGuard.isSheatheableFamily`（能不能裹）；
      ② `client/CamouflageShellTargets.isShellTarget`（给谁画外壳）。

用法: python tools/selfcheck_round49_camouflage_external_storage.py
退出码: 0 = 全部通过。
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
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

PROBLEMS = []
CHECKS = [0]


def check(ok, label, detail=""):
    CHECKS[0] += 1
    print("%s %s%s" % ("[PASS]" if ok else "[FAIL]", label, (" | " + detail) if detail else ""))
    if not ok:
        PROBLEMS.append(label)


def read(rel):
    with io.open(os.path.join(PKG, rel), "r", encoding="utf-8") as handle:
        return handle.read()


def code_only(src):
    """剥掉注释：锚点只认真实代码（javadoc 里会成段写类名与「不要怎么做」）。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def method_body(code, signature):
    start = code.find(signature)
    if start < 0:
        return ""
    brace = code.find("{", start)
    depth = 0
    for i in range(brace, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[brace:i + 1]
    return ""


WIRE = code_only(read(os.path.join("support", "RsccWireBlocks.java")))
GUARD = code_only(read(os.path.join("support", "SeparationFrameGuard.java")))
TARGETS = code_only(read(os.path.join("client", "CamouflageShellTargets.java")))
ITEM = code_only(read(os.path.join("item", "CamouflageFrameItem.java")))
INTERACTION = code_only(read(os.path.join("support", "RsccCamouflageInteraction.java")))
SEARCH = code_only(read(os.path.join("support", "RsccWireLinkSearch.java")))

print("=" * 100)
print("任务 A：伪装可以套上外部存储总线（输入输出总线本来就能套）")
print("=" * 100)

# ---------------------------------------------------------------- ① 新谓词
check("ExternalStorageBlock" in WIRE and "import com.refinedmods.refinedstorage.common.storage.externalstorage.ExternalStorageBlock;" in read(
    os.path.join("support", "RsccWireBlocks.java")),
    "①1 判据落在 RS 的方块类 ExternalStorageBlock（按类判定，不维护方块 id 清单）")
is_ext_body = method_body(WIRE, "public static boolean isExternalStorageBus(")
check("instanceof ExternalStorageBlock" in is_ext_body,
    "①2 isExternalStorageBus 就是一句 instanceof（与家族里其它谓词同一形态）", is_ext_body.strip())

# ---------------------------------------------------------------- ② 可穿行族未动
is_wire_body = method_body(WIRE, "public static boolean isWire(")
check("ExternalStorageBlock" not in is_wire_body,
    "②1 isWire（可穿行族：搜链路径成员）里没有外部存储总线 —— 延长型归属判定一字未动",
    is_wire_body.strip())
check("RsccWireBlocks.isWire" in SEARCH and "ExternalStorage" not in SEARCH,
    "②2 搜链读的仍然是 isWire（外部存储总线仍然不可穿行）")

# ---------------------------------------------------------------- ③ 两处清单同时覆盖
family_body = method_body(GUARD, "public static boolean isSheatheableFamily(")
check("RsccWireBlocks.isExternalStorageBus(state)" in family_body,
    "③1 可套壳族补上外部存储总线（isSheatheableFamily = 能不能裹）", family_body.strip())
check("RsccWireBlocks.isWire(state) || isFluidPipe(state) || isShaft(state)" in family_body,
    "③2 原有的三族一字未改（线缆 / 流体管道 / 传动杆仍在同一句话里）")
check("block instanceof com.refinedmods.refinedstorage.common.storage.externalstorage.ExternalStorageBlock"
      in TARGETS,
    "③3 外壳族同步覆盖外部存储总线（isShellTarget = 给谁画外壳），否则「能裹但看不见外壳」")

# ---------------------------------------------------------------- ④ 语义与既有伪装完全一致
check("SeparationFrameGuard.isSheathable" in ITEM and "RsccCamouflage.add(" in ITEM,
    "④1 伪装框架物品走的是共用的 isSheathable 入口（本次没有给它单开一条判定）")
check("RsccCamouflage" in INTERACTION and "RsccSheaths" not in family_body,
    "④2 可套族只回答「能不能套」，连接冻结 / 记录仍各自留在分隔框架与伪装框架自己的实现里")
check("isSheatheableFamily" in code_only(read(os.path.join("support", "RsccCamouflage.java"))),
    "④3 外壳的形状接管 / 属性继承都读同一个 isSheatheableFamily —— 外部存储总线自动获得"
    "「套上后按整格参与碰撞 / 选中」与「抗爆 / 挖速继承填充方块」这两条既有语义")

# ---------------------------------------------------------------- ⑤ 覆盖推演
FAMILY = {
    "CableBlock": True, "ImporterBlock": True, "ExporterBlock": True, "ExternalStorageBlock": True,
    "FluidPipeBlock": True, "EncasedPipeBlock": True, "AxisPipeBlock": True, "SmartFluidPipeBlock": True,
    "ShaftBlock": True,
    "PumpBlock": False, "Stone": False, "SequenceExecutionChamber": False,
}
TRAVERSABLE = {
    "CableBlock": True, "ImporterBlock": True, "ExporterBlock": True,
    "ExternalStorageBlock": False, "PumpBlock": False, "Stone": False,
}
EXPECT_FAMILY = {"CableBlock": True, "ImporterBlock": True, "ExporterBlock": True,
                 "ExternalStorageBlock": True, "FluidPipeBlock": True, "EncasedPipeBlock": True,
                 "AxisPipeBlock": True, "SmartFluidPipeBlock": True, "ShaftBlock": True,
                 "PumpBlock": False, "Stone": False, "SequenceExecutionChamber": False}
bad = [name for name, want in EXPECT_FAMILY.items() if FAMILY.get(name) != want]
print("       [可套壳推演] " + "、".join(sorted(k for k, v in FAMILY.items() if v)))
check(not bad, "⑤1 推演：线缆 / 输入总线 / 输出总线 / 外部存储总线 / 管道族 / 传动杆都可套", str(bad))
untouched = TRAVERSABLE["CableBlock"] and TRAVERSABLE["ImporterBlock"] and TRAVERSABLE["ExporterBlock"] \
    and not TRAVERSABLE["ExternalStorageBlock"]
check(untouched, "⑤2 推演：可穿行表保持原样（外部存储总线仍不可穿行）")
check(FAMILY["PumpBlock"] is False and FAMILY["Stone"] is False,
    "⑤3 推演：机器与普通方块仍然不套（类别判定，不是「什么都裹」）")

print("=" * 100)
print("检查项: %d；问题: %d" % (CHECKS[0], len(PROBLEMS)))
if PROBLEMS:
    for item in PROBLEMS:
        print("  [FAIL] %s" % item)
    sys.exit(1)
print("[result] 自检通过")
sys.exit(0)
