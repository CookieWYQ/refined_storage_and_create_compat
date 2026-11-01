# -*- coding: utf-8 -*-
"""round85：伪装框架「外壳渲染数据通道 + 连锁应用材质」自检。

用户反馈（本轮两件事）：
  ①「现在即使我手动把它一个一个放上去，也无法正确渲染它的这个外观」——外壳（材质）不渲染；
  ②「我想把一大片伪装框架全都连锁，然后把它们全都设定成某一种材质（例如黑曜石）」——连锁应用材质没实现。

本脚本断言的是「这两件事在源码层面成立」，并带**反例自证**（把错误的写法喂给同一批判据 ⇒ 必须判负）：

  A. 外壳数据只有一份来源：数据附件 → 模型数据（ClientLevel / 缺省 BlockAndTintGetter）→ 外壳模型；
     并且「写」这一步天然带存档脏标记 + 观察者下发（NeoForge 的 BlockEntity#setData）。
  B. 外壳模型对「引擎没有回调 gatherModelData」的渲染路径（物理化单格）也能出四元组 / 报出正确渲染层。
  C. 连锁应用材质：上限 / 去重 / 权限 / 距离 / 逐块容错 / 失败提示 / 先写后扣 / 整批一次重烘。
  D. 反例：只读不写、只写不 sync、只有 gatherModelData、无上限、静默失败、无逐块容错 ⇒ 全判负。

用法: python tools/selfcheck_round85_camouflage_render_chain.py
"""
import io
import json
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
PROBLEMS = []
CHECKS = [0]


def check(ok, label):
    CHECKS[0] += 1
    print(("[PASS] " if ok else "[FAIL] ") + label)
    if not ok:
        PROBLEMS.append(label)


def read_text(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def java(*parts):
    return read_text(os.path.join(PKG, *parts))


def code_only(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def body(text, signature):
    start = text.index(signature)
    open_index = text.index("{", start)
    depth = 0
    for i in range(open_index, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[open_index:i + 1]
    raise SystemExit("方法体未闭合: " + signature)


SHELL = code_only(java("client", "model", "CamouflageShellModel.java"))
MODEL_DATA = code_only(java("client", "model", "CamouflageModelData.java"))
CLIENT_MIXIN = code_only(java("mixin", "client", "CamouflageClientLevelModelDataMixin.java"))
GETTER_MIXIN = code_only(java("mixin", "client", "CamouflageGetterModelDataMixin.java"))
CAMOU = code_only(java("support", "RsccCamouflage.java"))
CHAIN = java("support", "RsccCamouflageChain.java")
CHAIN_CODE = code_only(CHAIN)
SHEATH = code_only(java("support", "RsccSheathChain.java"))
ULTIMINE = java("support", "RsccUltimineIntegration.java")
MIXINS = read_text(os.path.join(ROOT, "src", "main", "resources", "rs_create_compat.mixins.json"))

# ---------------------------------------------------------------- A 数据通道
print("=" * 100)
print("A. 外壳数据只有一份来源：附件 → 模型数据 → 外壳模型")
print("=" * 100)
check("public static final ModelProperty<RsccCamouflage.Camo> CAMOUFLAGE" in MODEL_DATA
      and "ModelData.of(CAMOUFLAGE, camo)" in MODEL_DATA,
      "A1 模型数据里只有一个伪装属性（ModelData.builder 之外的唯一构造入口 = CamouflageModelData）")
check(re.sub(r"\s+", "", "CAMOUFLAGE_PROPERTY=CamouflageModelData.CAMOUFLAGE;")
      in re.sub(r"\s+", "", SHELL),
      "A2 外壳模型里的 CAMOUFLAGE_PROPERTY 是同一个实例的别名（不是第二份状态）")

gather = body(SHELL, "protected ModelData.Builder gatherModelData(")
check("CamouflageModelData.camoOf(blockEntityData)" in gather
      and "RsccCamouflage.get(world, pos)" in gather
      and gather.index("camoOf(blockEntityData)") < gather.index("RsccCamouflage.get(world, pos)"),
      "A3 gatherModelData：先读引擎递进来的方块实体模型数据，读不到才回落问世界")

check("@Mixin(ClientLevel.class)" in CLIENT_MIXIN
      and 'method = "getModelData(Lnet/minecraft/core/BlockPos;)' in CLIENT_MIXIN
      and "RsccCamouflageAttachment.CAMOUFLAGE.get()" in CLIENT_MIXIN
      and "CamouflageModelData.withCamo(" in CLIENT_MIXIN,
      "A4 ClientLevel#getModelData 并上同一份附件（物理化单格把它的返回值直接交给 getQuads）")
check("@Mixin(BlockAndTintGetter.class)" in GETTER_MIXIN
      and "default ModelData getModelData(final BlockPos pos)" in GETTER_MIXIN
      and "getBlockEntity(pos)" in GETTER_MIXIN,
      "A5 没有覆写 getModelData 的 BlockAndTintGetter 默认问方块实体（同一份附件）")

mixins = json.loads(MIXINS)
check("client.CamouflageClientLevelModelDataMixin" in mixins.get("client", [])
      and "client.CamouflageGetterModelDataMixin" in mixins.get("client", [])
      and "client.CamouflageClientLevelModelDataMixin" not in mixins.get("mixins", []),
      "A6 两个新 Mixin 只登记在 client 段（专服不加载）")

write = body(CAMOU, "private static void write(")
check("be.setData(RsccCamouflageAttachment.CAMOUFLAGE.get(), camo)" in write
      and "notifyClient(level, List.of(be.getBlockPos()))" in write,
      "A7 单格写入 = setData（附件写 + 同步） + notifyClient（重烘），两步都在 write(...) 里")

# ---------------------------------------------------------------- B 不回调也能画
print("=" * 100)
print("B. 引擎不回调 gatherModelData 时照样出外壳（物理化单格）")
print("=" * 100)
quads = body(SHELL, "public List<BakedQuad> getQuads(")
check("base != null ? base :" in re.sub(r"\s+", " ", quads)
      and "originalModel.getQuads(state, side, rand, forwarded, renderType)" in quads,
      "B1 BASE_DATA_PROPERTY 缺席时把整份模型数据转交给被裹模型（线缆连接臂不丢）")
check("quads.addAll(shellQuads(state, side, rand, data, camo, renderType))" in quads,
      "B2 外壳四元组只由模型数据里的伪装记录驱动（不看被裹模型返回了几个四元组）")
check("shellLayers(camo, rand, data)" in body(SHELL, "public ChunkRenderTypeSet getRenderTypes(")
      and "modelOf(material).getRenderTypes(material, rand, wrappedData(data))"
      in body(SHELL, "private ChunkRenderTypeSet shellLayers("),
      "B3 缺阶段化层集合时按材质自己的模型现算（不读世界 / 坐标）")
check("model.getQuads(material, side, rand, wrapped, renderType)"
      in body(SHELL, "private List<BakedQuad> shellQuads("),
      "B4 材质分支画的是材质方块自己的模型（与 Create 伪装板同一句）")

# ---------------------------------------------------------------- C 连锁应用材质
print("=" * 100)
print("C. 连锁应用材质：上限 / 去重 / 权限 / 距离 / 容错 / 提示 / 守恒 / 同步")
print("=" * 100)
check("public static int chainApplyMaterial(" in CHAIN_CODE
      and "if (!RsccCamouflage.isCamouflaged(level, anchor))" in CHAIN_CODE,
      "C1 唯一入口，且只改已经是伪装格的格（命中格没裹住 ⇒ 交回 FTB / 原版）")
check("limit <= 0" in CHAIN_CODE and "targets.size() >= limit" in CHAIN_CODE
      and "targets.size() >= stack.getCount()" in CHAIN_CODE,
      "C2 上限 = FTB maxBlocks + 手持数量（普通模式）")
check("new LinkedHashSet<>()" in CHAIN_CODE, "C3 去重（LinkedHashSet，保序）")
check("player.mayBuild()" in CHAIN_CODE and "RsccBusClipboard.ULTIMINE_MAX_DISTANCE" in CHAIN_CODE
      and "withinReach(player, pos)" in CHAIN_CODE,
      "C4 权限 + 距离（复用总线连锁粘贴的 32 格上限）")
set_all = body(CAMOU, "public static BulkWrite setMaterialAll(")
check("try {" in set_all and "catch (final RuntimeException e)" in set_all
      and "be.setData(RsccCamouflageAttachment.CAMOUFLAGE.get()," in set_all
      and "notifyClient(level, written)" in set_all,
      "C5 批量写入：逐块 try/catch、逐格 setData、整批一次重烘")
check("Component.translatable(MSG_NO_TARGET)" in CHAIN_CODE
      and "Component.translatable(MSG_PARTIAL" in CHAIN_CODE
      and "Component.translatable(MSG_FULL_BLOCK_ONLY)" in CHAIN_CODE
      and "Component.translatable(MSG_CANNOT_BUILD)" in CHAIN_CODE,
      "C6 失败一定有提示（无可改格 / 部分失败 / 方块不能当外壳 / 没权限）")
check(CHAIN_CODE.index("RsccCamouflage.setMaterialAll(")
      < CHAIN_CODE.index("stack.consume(result.changed(), player)")
      and "CamouflageFrameItem.give(player, refund.copy())" in CHAIN_CODE,
      "C7 守恒：先写记录再扣新材质、旧外壳原样归还")

chain_body = body(code_only(ULTIMINE), "private static Object chain(")
check(chain_body.index("AllBlocks.CLIPBOARD.isIn(held)")
      < chain_body.index("RsccSheathChain.chainSheath(")
      < chain_body.index("RsccCamouflageChain.chainApplyMaterial(")
      and re.search(r"if\s*\(\s*framed\s*>\s*0\s*\)\s*\{\s*return\s+framed;", chain_body),
      "C8 触发区分：剪贴板 → 框架（>0 即返回）→ 材质（拿框架的连锁语义不变）")
check("public static List<BlockPos> planTargets(" in SHEATH
      and "RsccSheathChain.planTargets(" in CHAIN_CODE,
      "C9 同族规划复用 RsccSheathChain.planTargets（口径只有一份）")

# ---------------------------------------------------------------- D 反例自证
print("=" * 100)
print("D. 反例自证：错误写法必须被判据判负")
print("=" * 100)


def judge_write_sync(src):
    code = code_only(src)
    return "be.setData(" in code and "notifyClient(" in code


def judge_model_channel(src):
    code = code_only(src)
    return ("camoOf(blockEntityData)" in code
            and "RsccCamouflage.get(world, pos)" in code
            and "shellLayers(" in code)


def judge_chain_entry(src):
    code = code_only(src)
    return all(token in code for token in (
        "targets.size() >= limit", "LinkedHashSet", "player.mayBuild()",
        "displayClientMessage", "RsccCamouflage.setMaterialAll("))


def judge_batch_write(src):
    code = code_only(src)
    return all(token in code for token in (
        "catch (final RuntimeException", "be.setData(", "notifyClient(level, written)",
        "getExistingDataOrNull", "BulkWrite"))


COUNTER_ONLY_READ = "private static void write(Level l, BlockEntity be, Camo c) { be.getExistingDataOrNull(T); }"
COUNTER_WRITE_NO_SYNC = "private static void write(Level l, BlockEntity be, Camo c) { be.setData(T, c); }"
COUNTER_OLD_MODEL = (
    "protected ModelData.Builder gatherModelData(Builder b, BlockAndTintGetter w, BlockPos p, "
    "BlockState s, ModelData d) { Camo c = RsccCamouflage.get(w, p); if (c == null) { return b; } return b; }")
COUNTER_NO_LIMIT = (
    "int chainApplyMaterial(ServerPlayer player) { Set<BlockPos> targets = new LinkedHashSet<>(); "
    "if (!player.mayBuild()) { player.displayClientMessage(m, true); } "
    "return RsccCamouflage.setMaterialAll(level, targets, mat, stack).changed(); }")
COUNTER_SILENT = (
    "int chainApplyMaterial(ServerPlayer player, int limit) { Set<BlockPos> targets = new LinkedHashSet<>(); "
    "if (!player.mayBuild()) { return 0; } if (targets.size() >= limit) { } "
    "return RsccCamouflage.setMaterialAll(level, targets, mat, stack).changed(); }")
COUNTER_WRITE_ONLY = (
    "BulkWrite setMaterialAll(Level l, Collection<BlockPos> ps) { List<BlockPos> w = new ArrayList<>(); "
    "for (BlockPos p : ps) { BlockEntity be = l.getBlockEntity(p); be.setData(T, c); w.add(p); } "
    "return new BulkWrite(w.size(), 0, List.of()); }")

check(not judge_write_sync(COUNTER_ONLY_READ) and not judge_write_sync(COUNTER_WRITE_NO_SYNC)
      and judge_write_sync(code_only(CAMOU)),
      "D1 「只读不写」「只写不 sync」判负；真实写入路径判正")
check(not judge_model_channel(COUNTER_OLD_MODEL) and judge_model_channel(SHELL),
      "D2 「只有 gatherModelData 现问世界」的旧外壳模型判负；当前实现判正")
check(not judge_chain_entry(COUNTER_NO_LIMIT) and not judge_chain_entry(COUNTER_SILENT)
      and judge_chain_entry(CHAIN_CODE),
      "D3 「无上限」「静默失败」判负；真实连锁入口判正")
check(not judge_batch_write(COUNTER_WRITE_ONLY) and judge_batch_write(set_all),
      "D4 「没有逐块容错 / 不发重烘」的批量写入判负；真实实现判正")

print()
print("=" * 100)
print("检查项: %d；问题: %d" % (CHECKS[0], len(PROBLEMS)))
for entry in PROBLEMS:
    print("  - " + entry)
print("[result] " + ("自检通过" if not PROBLEMS else "自检失败"))
print("=" * 100)
sys.exit(1 if PROBLEMS else 0)
