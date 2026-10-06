#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 22 轮：RS「自动合成预览」界面显示「中间产物（优先复用）」——纯显示层，红线不动。

用户原话：「当『优先使用中间产物』开关打开时，这个预览界面应当能看出『本配方会优先复用中间产物』，
而不是只显示起步原料就完事。」

本脚本把这一轮的三条硬约束钉成可回归的锚点：
  1) <b>纯显示层</b>：只在 RS 的预览界面上多画一行，绝不动样板的 ingredient 构成
     （因此「开始」按钮的可用性、缺料判定、回退路径全都逐字不变）；
  2) <b>开关为关 ⇒ 界面逐字不变</b>：渲染入口第一句就是开关判断；
  3) <b>定位必须紧</b>：只认「本模组样板产物口径」的裸物品键 + 配方主产物，
     两条配方撞车时宁可不显示。

用法：python tools/selfcheck_round22_preview_intermediate_hint.py
退出码：0 = 没有任何 FAIL。
"""

from __future__ import annotations

import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
RES = os.path.join(ROOT, "src", "main", "resources")
LANG = os.path.join(RES, "assets", "rs_create_compat", "lang")
KEY = "gui.rs_create_compat.autocrafting_preview.intermediate_reuse"
FAILURES = []
CHECKS = [0]


def read(rel):
    with io.open(os.path.join(SRC, rel), "r", encoding="utf-8") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def has(text, needle, name):
    ok = needle in text
    check(name, ok, "" if ok else ("missing: %s" % needle))


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


mixin_dir = "mixin"
screen_mixin = read(os.path.join(mixin_dir, "client", "AutocraftingPreviewScreenMixin.java"))
menu_accessor = read(os.path.join(mixin_dir, "accessor", "AutocraftingPreviewMenuAccessor.java"))
req_accessor = read(os.path.join(mixin_dir, "accessor", "AutocraftingRequestAccessor.java"))
hint = read(os.path.join("client", "PreviewIntermediateHint.java"))
mirror = read(os.path.join("client", "IntermediateReuseClient.java"))
req_packet = read(os.path.join("network", "RequestIntermediateReusePacket.java"))
sync_packet = read(os.path.join("network", "SyncIntermediateReusePacket.java"))
main = read("RS_Create_Compat.java")
pattern_item = read(os.path.join("item", "SequenceAssemblyPatternItem.java"))
import_strategy = read(os.path.join("support", "RsccChamberImportStrategy.java"))
export_strategy = read(os.path.join("support", "RsccChamberExportStrategy.java"))
chamber = read(os.path.join("block", "entity", "SequenceExecutionChamberBlockEntity.java"))

with io.open(os.path.join(RES, "rs_create_compat.mixins.json"), "r", encoding="utf-8") as handle:
    mixin_cfg = json.load(handle)

# ======================================================================
# 1) 红线：产线语义一个字节都不动
# ======================================================================
section("1) 红线：样板的 ingredient 构成 / 产线语义文件不得被本功能触碰")

for name, text in (("SequenceAssemblyPatternItem", pattern_item),
                   ("RsccChamberImportStrategy", import_strategy),
                   ("RsccChamberExportStrategy", export_strategy),
                   ("SequenceExecutionChamberBlockEntity", chamber)):
    check("1a 红线：%s 不引用本功能的任何类型（中间产物只出现在显示层）" % name,
          "PreviewIntermediateHint" not in text and "IntermediateReuseClient" not in text
          and "AutocraftingPreviewScreen" not in text and "SyncIntermediateReusePacket" not in text)
has(pattern_item, "builder.output(new ItemResource(mainOutput.getItem(),",
    "1b 红线基线：样板产物仍是「裸物品键」（本功能的定位判据正是按这条口径写死的）")
new_files = ("client/IntermediateReuseClient.java", "client/PreviewIntermediateHint.java",
             "network/RequestIntermediateReusePacket.java", "network/SyncIntermediateReusePacket.java",
             "mixin/client/AutocraftingPreviewScreenMixin.java",
             "mixin/accessor/AutocraftingPreviewMenuAccessor.java",
             "mixin/accessor/AutocraftingRequestAccessor.java")
missing = [rel for rel in new_files if not os.path.isfile(os.path.join(SRC, rel))]
check("1c 本功能的新增文件都在（2 包 + 客户端镜像 + 解析助手 + 界面 Mixin + 2 访问器）",
      not missing, "missing=%s" % missing)

# ======================================================================
# 2) 开关为关 ⇒ 界面逐字不变
# ======================================================================
section("2) 开关为关 ⇒ 预览界面逐字不变（渲染入口第一句就退出）")

check("2a 渲染注入的第一句是开关判断，且在取过渡件之前",
      "if (!IntermediateReuseClient.enabled()) {\n            return;" in screen_mixin
      and screen_mixin.index("if (!IntermediateReuseClient.enabled())")
      < screen_mixin.index("rscc$transitional()"))
has(mirror, "private static volatile boolean enabled = false;",
    "2b 客户端镜像默认档 = 服务端默认档 = 关（快照未到时显示的就是服务端实际行为）")
has(sync_packet, "ctx.enqueueWork(() -> cretae.cookiewyq.rs_create_compat.client.IntermediateReuseClient.set(",
    "2c 只有 S2C 权威快照能写镜像；客户端从不自己改开关")
check("2d 界面 Mixin 只注入 init / render（目标类自身声明的方法；本工程硬规则）",
      sorted(__import__("re").findall(r'@Inject\(method = "([a-zA-Z]+)"', screen_mixin)) == ["init", "render"])
has(screen_mixin, "PacketDistributor.sendToServer(new RequestIntermediateReusePacket());",
    "2e init 末尾拉一次只读快照（预览界面没有服务端菜单，搭不上 ContainerData）")

# ======================================================================
# 3) 定位判据必须紧（宁可少说，也不说错）
# ======================================================================
section("3) 「这条预览属于本模组」的判据：裸物品键 + 配方主产物 + 撞车就不显示")

has(hint, "if (!itemResource.components().isEmpty()) {",
    "3a 带数据组件的请求项一律不认（本模组样板产物恒为裸物品键）")
has(hint, "final ItemStack main = pool.getFirst().getStack();",
    "3b 只认配方<b>主产物</b>（resultPool 第一项，与样板产物同一口径）")
has(hint, "return ItemStack.EMPTY; // 多条配方产出同一主产物 ⇒ 分不清，不显示",
    "3c 两条以上配方产出同一主产物 ⇒ 不显示（显示错的过渡件比不显示更糟）")
has(hint, "catch (final RuntimeException ignored) {",
    "3d 每帧渲染路径绝不外抛（配方系统未就绪按「不是本模组的配方」处理）")
has(hint, "final ItemStack transitional = hit.getTransitionalItem();",
    "3e 过渡件取 Create 配方自己的 getTransitionalItem()（与执行仓 / 样板终端同一口径）")
has(screen_mixin, "if (type != PreviewType.SUCCESS && type != PreviewType.MISSING_RESOURCES) {",
    "3f 只在「真的列出了所需物」的两种状态下画（报错态保持原样，不加噪音）")

# ======================================================================
# 4) 访问器 / 包注册 / 语言键
# ======================================================================
section("4) 访问器、payload 注册、语言键")

has(menu_accessor, '@Invoker("getCurrentRequest")', "4a 菜单访问器：getCurrentRequest（包私有 ⇒ 必须 @Invoker）")
for getter in ("getResource", "getPreview", "getTreePreview"):
    has(req_accessor, '@Invoker("%s")' % getter, "4b 请求访问器：%s" % getter)
check("4c mixin 配置 client 段登记了全部 3 个新 Mixin",
      all(entry in mixin_cfg.get("client", []) for entry in
          ("client.AutocraftingPreviewScreenMixin", "accessor.AutocraftingPreviewMenuAccessor",
           "accessor.AutocraftingRequestAccessor")),
      "client=%s" % mixin_cfg.get("client"))
for packet in ("RequestIntermediateReusePacket", "SyncIntermediateReusePacket"):
    has(main, "network.%s.TYPE," % packet, "4d %s 已在 registerPayloads 注册（漏注册会断连接）" % packet)
check("4e C2S 请求包在主类注册时套了 enqueueWork（处理体要读主世界 SavedData 并回包）",
      "RequestIntermediateReusePacket.handle(" in main
      and "(packet, ctx) -> ctx.enqueueWork(() ->" in main)
check("4f 请求包只读：不写任何服务端状态（只 sendToPlayer 一份快照）",
      "setReuseIntermediates" not in req_packet and "PacketDistributor.sendToPlayer" in req_packet)

zh = json.load(io.open(os.path.join(LANG, "zh_cn.json"), encoding="utf-8"))
en = json.load(io.open(os.path.join(LANG, "en_us.json"), encoding="utf-8"))
check("4g zh/en 键集合一致", set(zh) == set(en))
has(screen_mixin, '"%s"' % KEY, "4h 界面 Mixin 引用的就是这对键")
check("4i 键在中英两边都存在且非空", zh.get(KEY, "").strip() and en.get(KEY, "").strip())
text = zh.get(KEY, "")
check("4j 中文文案 ≤40 字符", len(text) <= 40, "len=%d" % len(text))
check("4k 文案不写「或」/「等 N 种」/「图标轮换」/种类数",
      "或" not in text and "等" not in text and "轮换" not in text
      and not __import__("re").search(r"\d", text))

print()
print("=" * 78)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
sys.exit(0)
