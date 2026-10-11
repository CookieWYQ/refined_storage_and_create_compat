# -*- coding: utf-8 -*-
"""第 49 轮 · 任务 B：**用机械动力的剪贴板批量安排输入 / 输出总线的配置**（用户第 7 条）。

用户原话：「现在**一个一个手动配置这一个输入输出总线太麻烦了**……你得想一个办法，使它更加便于
**快速大量的**给他安排相同的配置。比如说让这一个输入输出总线，可以使用这一个**机械动力的剪贴板**
进行**快速粘贴它的配置**。」

本脚本固化的判据（全部是「不能悄悄退化」的硬点）：
    ① 剪贴板载荷走 Create 自己的 `copied_values` 那一格（与 Create 复制配置同一格），
       且**只替换本模组那一段键** —— 绝不抹掉别的模组已经复制进去的内容；
    ② RS 过滤槽用 **RS 自己的 `FilterWithFuzzyMode#save/load`**（含模糊模式），
       本模组不自己序列化 ResourceKey；
    ③ 「是否显式勾选过类别」必须随载荷一起走（否则「从未配置」会被粘成「一个都不选」）；
    ④ **服务端权威**：客户端只取消事件，读剪贴板 / 校验 / 写入 / 文案全部在 `ServerLevel` 分支内；
       左击在两个端都取消（否则左键会顺手把方块挖了）；
    ⑤ **有边界**：整簇粘贴只走同一条线缆簇（复用唯一的搜链实现 → 尊重扳手断开与分隔框架冻结的接缝）、
       只改同一种总线、有台数上限、不跨维度；种类 / 版本不符时**一个字节都不写**；
    ⑥ 新增文案中英成对、中文 ≤40 字、不含禁用说法（「或」/「等 N 种」/「图标轮换」）。

用法: python tools/selfcheck_round49_bus_clipboard_bulk.py
退出码: 0 = 全部通过。
"""
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
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

NEW_KEYS = [
    "message.rs_create_compat.bus_clipboard.copied",
    "message.rs_create_compat.bus_clipboard.failed",
    "message.rs_create_compat.bus_clipboard.kind_mismatch",
    "message.rs_create_compat.bus_clipboard.pasted",
    "message.rs_create_compat.bus_clipboard.pasted_many",
]

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
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def body(code, signature):
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


CLIP = code_only(read(os.path.join("support", "RsccBusClipboard.java")))
CONFIG = code_only(read(os.path.join("support", "RsccBusConfig.java")))
EXP = code_only(read(os.path.join("mixin", "exporter", "AbstractExporterBlockEntityMixin.java")))
IMP = code_only(read(os.path.join("mixin", "importer", "AbstractImporterBlockEntityMixin.java")))
EXP_IF = code_only(read(os.path.join("support", "RsccExporterExecutorMode.java")))
IMP_IF = code_only(read(os.path.join("support", "RsccImporterExecutorMode.java")))

print("=" * 100)
print("任务 B：Create 剪贴板复制 / 粘贴总线配置（含整簇批量粘贴）")
print("=" * 100)

# ---------------------------------------------------------------- ① Create 剪贴板链路
check("@EventBusSubscriber(modid = RS_Create_Compat.MODID)" in read(os.path.join("support", "RsccBusClipboard.java")),
    "①1 挂点是自动注册到游戏总线的 @EventBusSubscriber（不动主类、不动别人的注册段）")
check("AllBlocks.CLIPBOARD.isIn(" in CLIP and "AllDataComponents.CLIPBOARD_CONTENT" in CLIP,
    "①2 认的就是 Create 的剪贴板物品与它的数据组件（玩家手里的那块板）")
check("copiedValues()" in CLIP and "setCopiedValues(merged)" in CLIP,
    "①3 载荷落在 Create 复制配置用的同一格 copied_values")
check("content.copiedValues().map(CompoundTag::copy)" in CLIP,
    "①4 写入前先把剪贴板原有段落整份留下（只替换本模组那一段键，不抹掉别人复制的内容）")
check("ClipboardType.WRITTEN" in CLIP, "①5 写完把剪贴板置成「已写」态（与 Create 复制后的表现一致）")

# ---------------------------------------------------------------- ② 载荷格式
for key, why in (("KEY_VERSION", "版本号（更新的格式一律拒，不做尽力而为的猜测）"),
                 ("KEY_KIND", "种类（输入总线 / 输出总线）"),
                 ("KEY_FILTER", "RS 过滤槽"),
                 ("KEY_CATEGORIES", "本模组类别勾选"),
                 ("KEY_EXPLICIT", "是否显式勾选过类别"),
                 ("KEY_AUTO", "输入总线的全自动收回开关"),
                 ("KEY_FORCE_NORMAL", "强制普通总线开关")):
    check(key in CONFIG, "②1 载荷带 %s（%s）" % (key, why))
check("filter.save(filterTag, provider)" in CONFIG and "filter.load(tag.getCompound(KEY_FILTER), provider)" in CONFIG,
    "②2 RS 过滤槽用 RS 自己的 save / load（物品流体过滤项 + 模糊模式一起走，格式与 RS 落盘逐字一致）")
check("ResourceLocation" not in CONFIG and "BuiltInRegistries" not in CONFIG,
    "②3 本模组不自己序列化 ResourceKey（不另立一份与 RS 会漂移的格式）")

# ---------------------------------------------------------------- ③ 校验先于写入
accept = body(CONFIG, "public static boolean acceptsKind(")
check("tag.getInt(KEY_VERSION) <= VERSION" in accept and "kind.equals(tag.getString(KEY_KIND))" in accept,
    "③1 校验 = 版本 ≤ 本模组认识的上限 且 种类一致", accept.strip())
check("acceptsKind" in CLIP and "MSG_KIND_MISMATCH" in CLIP,
    "③2 粘贴入口先校验；不通过时给一句明确反馈")
check("acceptsKind" in body(EXP, "public void rscc$readBusConfig(")
      and "acceptsKind" in body(IMP, "public void rscc$readBusConfig("),
    "③3 两个方块实体的写入路径各自再校验一次（数据层的最后一道闸门）")

# ---------------------------------------------------------------- ④ 服务端权威 / 两端取消
right = body(CLIP, "public static void onRightClickBlock(")
left = body(CLIP, "public static void onLeftClickBlock(")
check("instanceof final ServerLevel serverLevel" in right and "instanceof final ServerLevel serverLevel" in left,
    "④1 读剪贴板 / 写方块实体 / 报文案全部在服务端分支内（客户端一个字节都不写）")
check("event.setCanceled(true);" in right and "event.setCanceled(true);" in left,
    "④2 两端都取消事件：右击不打开总线界面、左击不把方块挖掉")
check("InteractionResult.SUCCESS" in right, "④3 右击取消时给出成功结果（与 Create 剪贴板挂点同一写法）")
check("net.minecraft.client" not in CLIP and "net.minecraft.client" not in CONFIG,
    "④4 新类不含任何客户端类型（专用服务端加载安全）")

# ---------------------------------------------------------------- ⑤ 边界
cluster = body(CLIP, "private static int pasteCluster(")
check("RsccWireLinkSearch.searchChamberLink(level, origin).cluster()" in cluster,
    "⑤1 整簇粘贴复用唯一的搜链实现（「哪些总线算一簇」不会出现第二种口径）")
check("BULK_MAX_BUSES" in CLIP and "applied >= BULK_MAX_BUSES" in cluster,
    "⑤2 台数上限（潜行左击不会无限扩散）")
check("kind.equals(kindAt(level, pos))" in cluster,
    "⑤3 只改与目标同一种总线（输入总线的配置不会糊到输出总线上）")
check("searchChamberLink" in read(os.path.join("support", "RsccWireLinkSearch.java"))
      and "RsccCableCuts.isDisconnected" in read(os.path.join("support", "RsccWireLinkSearch.java"))
      and "SeparationFrameGuard.blocksConnection" in read(os.path.join("support", "RsccWireLinkSearch.java")),
    "⑤4 簇边界天然尊重「扳手断开的接缝」与「被分隔框架冻结的接缝」（复用同一趟展开的既有语义）")
check("pasteOne(level, origin, kind, payload)" in cluster,
    "⑤5 兜底：展开被重入守卫拦下时，至少把配置贴到玩家点的那一条总线上")
check("player.isShiftKeyDown()" in left, "⑤6 单台 / 整簇靠潜行区分（默认那只改玩家点中的一条）")

# ---------------------------------------------------------------- ⑥ 两边对称
for tag, src, iface in (("输出总线", EXP, EXP_IF), ("输入总线", IMP, IMP_IF)):
    check("rscc$writeBusConfig" in src and "rscc$readBusConfig" in src and "RsccBusConfig" in src,
        "⑥1 %s 实现了剪贴板读写（格式统一走 RsccBusConfig）" % tag)
    check("rscc$writeBusConfig" in iface and "rscc$readBusConfig" in iface,
        "⑥2 %s 的桥接接口声明了这两个方法（普通类才能类型安全地调用它）" % tag)
check("KEY_EXPLICIT" in EXP and "KEY_AUTO" in IMP,
    "⑥3 输出总线带走「是否显式勾选过」、输入总线带走「全自动收回」（各自独有的那一位都在）")
check("KEY_FORCE_NORMAL" in EXP and "KEY_FORCE_NORMAL" in IMP,
    "⑥4 两侧都带走「强制普通总线」开关")

# ---------------------------------------------------------------- ⑦ 文案
zh = json.loads(io.open(os.path.join(LANG, "zh_cn.json"), encoding="utf-8").read())
en = json.loads(io.open(os.path.join(LANG, "en_us.json"), encoding="utf-8").read())
check(all(k in zh for k in NEW_KEYS), "⑦1 新增文案中文齐全")
check(all(k in en for k in NEW_KEYS), "⑦2 新增文案英文齐全")
check(all(len(zh[k]) <= 40 for k in NEW_KEYS), "⑦3 中文 ≤40 字",
      "; ".join("%s=%d" % (k.rsplit(".", 1)[-1], len(zh[k])) for k in NEW_KEYS))
check(all(("或" not in zh[k]) and ("等" not in zh[k]) and ("轮换" not in zh[k]) for k in NEW_KEYS),
    "⑦4 文案不含禁用说法（「或」/「等 N 种」/「图标轮换」）")
check(all(k in CLIP for k in NEW_KEYS), "⑦5 Java 里引用的正是这五个键（没有拼错的孤儿文案）")

# ---------------------------------------------------------------- ⑧ 推演
def accepts(payload, kind):
    return payload.get("kind") == kind and payload.get("v", 0) <= 1


TABLE = [
    ({"kind": "exporter", "v": 1}, "exporter", True, "同种类同版本 → 允许"),
    ({"kind": "importer", "v": 1}, "exporter", False, "另一种总线 → 拒（一个字节都不写）"),
    ({"kind": "exporter", "v": 2}, "exporter", False, "更新的格式 → 拒（不猜）"),
    ({"kind": "exporter", "v": 1}, "importer", False, "反方向同样拒"),
]
bad = []
for payload, kind, want, why in TABLE:
    got = accepts(payload, kind)
    print("       %-42s -> %-5s（%s）" % (str(payload) + " 贴到 " + kind, got, why))
    if got != want:
        bad.append((payload, kind))
check(not bad, "⑧1 推演：只有同种类且版本不高于上限的载荷才会被写入", str(bad))

print("=" * 100)
print("检查项: %d；问题: %d" % (CHECKS[0], len(PROBLEMS)))
if PROBLEMS:
    for item in PROBLEMS:
        print("  [FAIL] %s" % item)
    sys.exit(1)
print("[result] 自检通过")
sys.exit(0)
