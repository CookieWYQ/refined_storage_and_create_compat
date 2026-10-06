# -*- coding: utf-8 -*-
"""伪装框架（camouflage_frame）自检：源码锚点 + 资源齐全性 + 规则推演。

覆盖任务要求的 6 项：
  A. 注册 / 配方 / 语言键 / 贴图 齐全（缺一样游戏里就是「隐形方块 / 无配方 / 显示为键名」）
  B. 只接受完整方块：推演「台阶 / 栅栏 / 火把 → 被拒且有提示」「石头 / 玻璃 → 应用成功」，
     并证明这条规则来自 Create（本模组没有覆写判定）
  C. 外观渲染：取的确实是「外壳方块状态」自己的模型；外壳经方块实体模型数据下发，服务端权威；
     并证明「没装材质时有不透明兜底外观」（用户实测的「穿模 / 透视」问题）
  D. 与线缆 / 管道的共存：本方块不接入任何连接判定入口，线缆连接逻辑与改动前一致
  E. 持久化与破坏掉落守恒：存/取外壳 + 掉落不复制不销毁
  F. 只能装在管道 / 线缆上（用户要求「伪装框也是一样的」）

用法: python tools/selfcheck_camouflage_frame.py
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
NS = "rs_create_compat"
BLOCK = "camouflage_frame"

FILES = {
    "main": os.path.join(PKG, "RS_Create_Compat.java"),
    "block": os.path.join(PKG, "block", "CamouflageFrameBlock.java"),
    "item": os.path.join(PKG, "item", "CamouflageFrameItem.java"),
    "model": os.path.join(PKG, "client", "model", "CamouflageFrameModel.java"),
    "client": os.path.join(PKG, "client", "ClientInit.java"),
    # 2026-09-30（本轮架构重构）：外壳不再由独立渲染器画，而是成为「被裹方块自己模型的一部分」；
    # 旧的 client/CamouflageRenderer 与 network/SyncCamouflagePacket 已删除。
    "shell_model": os.path.join(PKG, "client", "model", "CamouflageShellModel.java"),
    "shell_targets": os.path.join(PKG, "client", "CamouflageShellTargets.java"),
    "attachment": os.path.join(PKG, "support", "RsccCamouflageAttachment.java"),
    "refresh": os.path.join(PKG, "network", "RefreshCamouflagePacket.java"),
    "removal_mixin": os.path.join(PKG, "mixin", "block", "CamouflageRemovalMixin.java"),
    "interaction": os.path.join(PKG, "support", "RsccCamouflageInteraction.java"),
    "camouflage": os.path.join(PKG, "support", "RsccCamouflage.java"),
    # 共用入口（「可套壳方块」判定；伪装框架与分隔框架共用同一条口径）
    "guard": os.path.join(PKG, "support", "SeparationFrameGuard.java"),
    # 复用对象（Create 源码，仓库内 local_src 副本）
    "copycat_block": os.path.join(ROOT, "local_src", "external", "Create", "src", "main", "java",
                                  "com", "simibubi", "create", "content", "decoration", "copycat",
                                  "CopycatBlock.java"),
    "copycat_be": os.path.join(ROOT, "local_src", "external", "Create", "src", "main", "java",
                               "com", "simibubi", "create", "content", "decoration", "copycat",
                               "CopycatBlockEntity.java"),
    "copycat_model": os.path.join(ROOT, "local_src", "external", "Create", "src", "main", "java",
                                  "com", "simibubi", "create", "content", "decoration", "copycat",
                                  "CopycatModel.java"),
}
RESOURCE = {
    "blockstate": os.path.join(ROOT, "src", "main", "resources", "assets", NS, "blockstates",
                               "%s.json" % BLOCK),
    "block_model": os.path.join(ROOT, "src", "main", "resources", "assets", NS, "models", "block",
                                "%s.json" % BLOCK),
    "item_model": os.path.join(ROOT, "src", "main", "resources", "assets", NS, "models", "item",
                               "%s.json" % BLOCK),
    "recipe": os.path.join(ROOT, "src", "main", "resources", "data", NS, "recipe",
                           "%s.json" % BLOCK),
    "loot": os.path.join(ROOT, "src", "main", "resources", "data", NS, "loot_table", "blocks",
                         "%s.json" % BLOCK),
    "lang_cn": os.path.join(ROOT, "src", "main", "resources", "assets", NS, "lang", "zh_cn.json"),
    "lang_en": os.path.join(ROOT, "src", "main", "resources", "assets", NS, "lang", "en_us.json"),
    "tex_side": os.path.join(ROOT, "src", "main", "resources", "assets", NS, "textures", "block",
                             "%s_side.png" % BLOCK),
    "tex_top": os.path.join(ROOT, "src", "main", "resources", "assets", NS, "textures", "block",
                            "%s_top.png" % BLOCK),
    "tex_bottom": os.path.join(ROOT, "src", "main", "resources", "assets", NS, "textures", "block",
                               "%s_bottom.png" % BLOCK),
}

LANG_KEYS = [
    "block.rs_create_compat.camouflage_frame",
    "block.rs_create_compat.camouflage_frame.help",
    "block.rs_create_compat.camouflage_frame.hint.full_only",
    "block.rs_create_compat.camouflage_frame.hint.occupied",
    "block.rs_create_compat.camouflage_frame.hint.pipe_only",
    # 2026-09-25（第 3 次反馈）新增：包裹 / 取下的完整反馈链
    "block.rs_create_compat.camouflage_frame.hint.wrapped",
    "block.rs_create_compat.camouflage_frame.hint.remove_hint",
    "block.rs_create_compat.camouflage_frame.hint.unwrapped",
    "block.rs_create_compat.camouflage_frame.hint.shelled",
    "block.rs_create_compat.camouflage_frame.hint.take_off_hint",
]

PROBLEMS = []
CHECKS = [0]


def check(ok, label, detail=""):
    CHECKS[0] += 1
    print("%s %s%s" % ("[PASS]" if ok else "[FAIL]", label, (" | " + detail) if detail else ""))
    if not ok:
        PROBLEMS.append(label)


def warn(label, detail=""):
    print("[WARN] %s%s" % (label, (" | " + detail) if detail else ""))


def read(key):
    with io.open(FILES[key], "r", encoding="utf-8") as handle:
        return handle.read()


def read_text(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def code_only(src):
    """剥掉注释：锚点只认真实代码（javadoc 里会写方法名与「不要怎么做」）。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def load_json(path):
    return json.loads(read_text(path))


# ------------------------------------------------------------------ A 齐全性

def check_registration_and_resources():
    main = read("main")
    check('BLOCKS.register("%s"' % BLOCK in main, "A1 方块已注册")
    check('ITEMS.register("%s"' % BLOCK in main, "A2 物品已注册")
    check('BLOCK_ENTITIES.register("%s"' % BLOCK in main, "A3 方块实体类型已注册")
    check("output.accept(CAMOUFLAGE_FRAME_ITEM.get())" in main, "A4 已进本模组创造栏")
    frame_segment = main.split('ITEMS.register("%s"' % BLOCK)[1].split(";")[0]
    check("CamouflageFrameItem(" in frame_segment, "A5 物品注册的是带约束的 CamouflageFrameItem")
    check("extends RsccHelpBlockItem" in code_only(read("item")),
          "A5b 该物品继承本模组的 RsccHelpBlockItem（帮助提示走 RS 原生常显 HelpTooltipComponent）")

    # 资源文件存在
    for key, path in RESOURCE.items():
        check(os.path.exists(path), "A6 资源存在：%s" % key)

    # 方块状态底模 = 本方块自己的立方体模型（2026-09-25 修正：原来是 minecraft:block/air，
    # 导致「没装材质」时什么都不画 → 能看穿到底下，用户报的「穿模 / 透视」）
    state = load_json(RESOURCE["blockstate"])
    check(state["variants"][""]["model"] == "%s:block/%s" % (NS, BLOCK),
          "A7 方块状态底模 = 本方块自己的立方体模型（供「没装材质」时兜底，不再透视）",
          json.dumps(state["variants"], ensure_ascii=False))
    item_model = load_json(RESOURCE["item_model"])
    check(item_model["parent"] == "%s:block/%s" % (NS, BLOCK), "A8 物品模型指向本方块模型",
          item_model["parent"])

    # 贴图真的被模型引用，且尺寸 16x16
    block_model = load_json(RESOURCE["block_model"])
    tex = block_model.get("textures", {})
    used = {v.split(":")[1] for v in tex.values() if ":" in v and v.startswith(NS + ":")}
    expect = {"block/%s_side" % BLOCK, "block/%s_top" % BLOCK, "block/%s_bottom" % BLOCK}
    check(used == expect, "A9 模型引用三张占位贴图（竖直四面 / 顶 / 底）", ", ".join(sorted(used)))
    try:
        from PIL import Image
        sizes = []
        for key in ("tex_side", "tex_top", "tex_bottom"):
            with Image.open(RESOURCE[key]) as image:
                sizes.append("%s=%s" % (os.path.basename(RESOURCE[key]), image.size))
        ok = all(s.endswith("(16, 16)") for s in sizes)
        check(ok, "A10 贴图尺寸均为 16×16", "; ".join(sizes))
    except ImportError:
        warn("A10 未安装 Pillow，跳过贴图尺寸检查（改用文件头自检）")
        for key in ("tex_side", "tex_top", "tex_bottom"):
            with open(RESOURCE[key], "rb") as handle:
                head = handle.read(24)
            check(head[:8] == b"\x89PNG\r\n\x1a\n" and head[16:24] == bytes([0, 0, 0, 16, 0, 0, 0, 16]),
                  "A10 PNG 头 16×16：%s" % key)

    # 语言键：中英都要有，且键名与 Java 里用的完全一致
    cn = load_json(RESOURCE["lang_cn"])
    en = load_json(RESOURCE["lang_en"])
    for key in LANG_KEYS:
        check(key in cn and key in en, "A11 语言键齐全：%s" % key,
              "zh=%s" % cn.get(key, "<缺失>"))
    # 提示键可能出现在三处：方块（旧存档里放下的伪装框架方块那条 Create 流程）、
    # 物品（裹上 / 取下）、交互挂点（选外壳 / 潜行取下 / 空手取下），因此三处都要扫。
    prompts_src = code_only(read("block")) + code_only(read("item")) + code_only(read("interaction"))
    for key in LANG_KEYS[2:]:
        check('"%s"' % key in prompts_src, "A12 Java 里引用的提示键与之匹配：%s" % key)


def check_recipe_and_loot():
    recipe = load_json(RESOURCE["recipe"])
    ids = set()
    for entry in recipe["key"].values():
        ids.add(entry["item"])
    result = recipe["result"]
    check(result["id"] == "%s:%s" % (NS, BLOCK), "A13 配方产物 = 本方块物品", result["id"])
    # 用户第 3 条：伪装框架「一次合成 32 个」（外观 / 材质设计不变，不要改成分隔框架）
    check(result.get("count") == 32, "A13b 伪装框架一次产出 32 个", "实际 %s 个" % result.get("count"))
    check(recipe["type"] in ("minecraft:crafting_shaped", "minecraft:crafting_shapeless"),
          "A14 配方类型在项目校验范围内", recipe["type"])
    if recipe["type"] == "minecraft:crafting_shaped":
        pattern = recipe["pattern"]
        used = {ch for row in pattern for ch in row if ch != " "}
        check(len(pattern) == 3 and all(len(r) == 3 for r in pattern) and used == set(recipe["key"]),
              "A15 有序配方 3×3 且 key 无多余字符")
    check(ids == {"create:zinc_ingot", "create:copycat_panel", "refinedstorage:quartz_enriched_iron"},
          "A16 配方原料（Create 伪装板同族 + 锌锭 + RS 石英强化铁）", ", ".join(sorted(ids)))

    # 战利品表：只掉「框架」自己一个，且不夹带外壳（外壳由方块实体 onRemove 单独归还）
    loot = load_json(RESOURCE["loot"])
    pools = loot["pools"]
    names = [e["name"] for p in pools for e in p["entries"]]
    check(len(pools) == 1 and names == ["%s:%s" % (NS, BLOCK)],
          "A17 战利品表只掉框架本体（外壳不由战利品表产出，避免复制）", ", ".join(names))


# ------------------------------------------------------------------ B 只接受完整方块

# 按 CopycatBlock#getAcceptedBlockState 的判定顺序做纯逻辑推演：entry = 方块物品的静态属性
CANDIDATES = [
    ("minecraft:stone", dict(full=True), True, "完整方块 → 接受"),
    ("minecraft:glass", dict(full=True), True, "完整方块（透明）→ 接受"),
    ("minecraft:oak_slab", dict(full=False), False, "非完整方块（半砖）→ 拒绝"),
    ("minecraft:oak_fence", dict(full=False), False, "非完整方块（栅栏）→ 拒绝"),
    ("minecraft:torch", dict(full=False), False, "非完整方块（火把，外形为空）→ 拒绝"),
    ("minecraft:glass_pane", dict(full=False), False, "非完整方块（玻璃板）→ 拒绝"),
    ("minecraft:iron_bars", dict(full=False), False, "非完整方块（铁栏杆）→ 拒绝"),
    ("minecraft:oak_stairs", dict(full=False, stair=True), False, "楼梯 → Create 显式拒绝"),
    ("minecraft:chest", dict(full=True, entity_block=True), False, "方块实体方块 → Create 拒绝"),
    ("create:copycat_panel", dict(full=True, copycat=True), False, "伪装板家族 → Create 拒绝"),
]


def create_accepts(entry):
    """复刻 Create 的判定链（顺序与 CopycatBlock#getAcceptedBlockState 一致）。"""
    if entry.get("copycat"):
        return False, "方块属于 CopycatBlock 家族"
    if entry.get("allow_tag"):
        return True, "命中 create:copycat_allow 标签（数据包可放行）"
    if entry.get("deny_tag"):
        return False, "命中 create:copycat_deny 标签（数据包可禁）"
    if entry.get("entity_block"):
        return False, "方块实体方块（EntityBlock）"
    if entry.get("stair"):
        return False, "楼梯被显式排除"
    if not entry.get("full"):
        return False, "外形或碰撞形状 != 整格（非完整方块）"
    return True, "完全匹配"


def check_full_block_only():
    block_src = code_only(read("block"))
    try:
        create_src = code_only(read("copycat_block"))
        order = []
        for token in ("COPYCAT_ALLOW.matches", "COPYCAT_DENY.matches", "instanceof EntityBlock",
                      "instanceof StairBlock", "Shapes.block()", "getCollisionShape"):
            order.append((token, create_src.find(token)))
        check(all(pos > 0 for _, pos in order) and order == sorted(order, key=lambda kv: kv[1]),
              "B1 规则链取自 Create 且顺序一致（allow/deny 标签 → 方块实体 → 楼梯 → 整格外形 → 碰撞非空）")
        check("COPYCAT_ALLOW.matches" in create_src and "COPYCAT_DENY.matches" in create_src,
              "B2 沿用 Create 的 data tag（create:copycat_allow / create:copycat_deny 可被数据包改写）")
    except IOError:
        warn("B1/B2 未找到 Create 源码副本（local_src），跳过「规则链来自 Create」的锚点检查")

    check("public BlockState getAcceptedBlockState" not in block_src
          and "getAcceptedBlockState(Level" not in block_src,
          "B3 本模组没有覆写判定（只在提示里读判定结果；规则 100% 来自 Create，伪装板收什么它就收什么）")

    # 推演
    wrong = []
    for block_id, props, expected, note in CANDIDATES:
        accepted, why = create_accepts(props)
        print("       %-28s -> %-5s (%s)%s"
              % (block_id, "接受" if accepted else "拒绝", why, "" if accepted == expected else "  << 与预期不符"))
        if accepted != expected:
            wrong.append(block_id)
    check(not wrong, "B4 推演：完整方块被接受、非完整方块/楼梯/方块实体方块被拒绝", "异常项: %s" % wrong)

    # 被拒时的提示（只读判定结果，不改任何逻辑）
    check("super.useItemOn(" in block_src and "return result;" in block_src,
          "B5 提示逻辑在 super 之上「只读判定结果并原样返回」，不改变能否应用")
    check("displayClientMessage" in block_src and "!level.isClientSide()" in block_src,
          "B6 提示只在服务端下发（动作栏），不会双端各说一次")
    check("instanceof final BlockItem" in block_src and "HINT_FULL_BLOCK_ONLY" in block_src
          and "HINT_ALREADY_SHEATHED" in block_src,
          "B7 提示分两种：非完整方块 / 已装外壳；手持非方块物品时不打扰玩家")


# ------------------------------------------------------------------ C 外观渲染

def check_appearance_rendering():
    model_src = code_only(read("model"))
    check("getModelOf(material).getQuads(material," in model_src,
          "C1 外观 = 外壳方块状态自己的模型（getModelOf(BlockState) → getQuads(BlockState)）")
    check("getModelOf(material).getRenderTypes(material," in model_src,
          "C2 渲染层跟随外壳材质（玻璃 / 树叶等 cutout 材质不会整块消失）")
    check("extends CopycatModel" in model_src,
          "C3 继承 Create 的 CopycatModel：材质来源、遮挡剔除、发光、材质模型数据转发全部复用")

    # C9~C11：没装材质时的不透明兜底（修用户的「穿模 / 透视」）
    check("hasNoMaterial" in model_src and "AllBlocks.COPYCAT_BASE.has" in model_src,
          "C9 判据：材质是 Create 的占位状态 copycat_base（那张贴图是透明的）→ 视为「没装材质」")
    check("originalModel.getQuads" in model_src,
          "C10 没装材质时改画本方块自己的兜底立方体（originalModel，不经过材质逻辑 → 不会递归）")
    check("originalModel.getRenderTypes" in model_src and "originalModel.getParticleIcon" in model_src,
          "C11 渲染层与粒子图标同样走兜底（玻璃感 / 半透明粒子都不会冒出来）")

    copycat_model_src = code_only(read("copycat_model"))
    reasons = [
        ("MATERIAL_PROPERTY", "外壳材质走方块实体的模型数据（ModelProperty<BlockState>）"),
        ("WRAPPED_DATA_PROPERTY", "外壳材质自身的模型数据被转发（连接纹理类方块靠它）"),
        ("IS_EMISSIVE_PROPERTY", "外壳材质发光时整块按发光渲染"),
    ]
    for token, why in reasons:
        check(token in copycat_model_src, "C4 复用 Create 的%s" % why)

    client_src = code_only(read("client"))
    check("ModelSwapper.swapModels(models," in client_src
          and "getAllBlockStateModelLocations" in client_src
          and "new CamouflageShellModel(original, frameModel)" in client_src,
          "C5 客户端在烘焙结果里替换「外壳族」全部状态的模型（不重烘焙、不重载资源）")
    check("CopycatBlock.wrappedColor()" in client_src,
          "C6 外壳材质的生物群系着色（草方块等）跟着材质走")

    # 2026-09-30（本轮架构重构）：外壳渲染的取数来源 = 被裹方块自己的方块实体上的附件。
    # 旧实现（按世界坐标回头查伪装记录 + 独立渲染器）已彻底删除，见下方 C12~C15。
    shell_src = code_only(read("shell_model"))
    check("extends BakedModelWrapperWithData" in shell_src
          and "RsccCamouflage.get(world, pos)" in shell_src,
          "C12 外壳模型与 Create 的 CopycatModel 同一个基类，且取数 = 被裹方块自己的方块实体（不是坐标反查）")
    for banned in ("SavedData", "mirrored", "renderCells", "getBlockState(", "LevelRenderer"):
        check(banned not in shell_src, "C13 外壳模型里没有「%s」（旧的坐标反查 / 独立渲染链路已删除）" % banned)
    check("originalModel.getQuads(state, side, rand, base == null ? ModelData.EMPTY : base, renderType)"
          in shell_src
          and "frameModel.getQuads(" in shell_src,
          "C14 一次产出两套几何：被裹方块自己的 + 外壳的（框架开口里看得见线缆的断开态）")
    check("ChunkRenderTypeSet.of(types)" in shell_src,
          "C15 渲染层取并集（被裹方块自己的 ∪ 外壳的），漏一层那层几何就永远不会被画")

    be_src = code_only(read("copycat_be"))
    check("getModelData()" in be_src and "MATERIAL_PROPERTY" in be_src,
          "C7 材质由方块实体作为模型数据暴露给渲染器（服务端权威 → 客户端只负责画）")
    check("requestModelDataUpdate" in be_src and "sendBlockUpdated" in be_src,
          "C8 客户端收到新材质后刷新模型数据并触发区块重绘")

    print("       [不透视推演] 没装材质 → 材质 = create:copycat_base（贴图全透明）→")
    print("                    本模型改画 rs_create_compat:block/camouflage_frame 的不透明立方体")
    print("                    → 四面都有实体外观，任何角度都看不穿；装了材质则照旧画材质模型。")


# ------------------------------------------------------------------ D 与线缆/管道共存

SHARED_CONNECTION_FILES = [
    os.path.join(PKG, "mixin", "network", "InWorldNetworkNodeContainerImplMixin.java"),
    os.path.join(PKG, "support", "RsccWireBlocks.java"),
    os.path.join(PKG, "support", "RsccWireLinkSearch.java"),
    os.path.join(PKG, "support", "RsccCableCuts.java"),
    os.path.join(PKG, "support", "RsccMachineCluster.java"),
]


def check_wire_coexistence():
    block_src = code_only(read("block"))
    forbidden = ["CableBlock", "ExporterBlock", "ImporterBlock", "PipeBlock", "FluidPipe",
                 "Capabilities", "NetworkNode", "canAcceptIncomingConnection", "RsccClusterable"]
    hits = [token for token in forbidden if token in block_src]
    check(not hits, "D1 本方块不含任何线缆 / 管道 / 网络节点 / 集群相关的挂接", "命中: %s" % hits)

    main_src = read("main")
    frame_segment = main_src.split('BLOCKS.register("%s"' % BLOCK)[1].split("// 创造模式标签页")[0]
    check("registerCapabilities" not in frame_segment and "Capabilities" not in frame_segment,
          "D2 伪装框架的方块实体没有注册任何能力（RS 的连接判定只对「有节点容器的方块」返回 true）")
    check("CopycatBlockEntity" in frame_segment,
          "D3 方块实体就是 Create 的 CopycatBlockEntity（没有藏任何网络 / 管道逻辑）")

    # 本批只「新增」，绝不改共享连接判定入口
    touched = []
    for path in SHARED_CONNECTION_FILES:
        if not os.path.exists(path):
            continue
        with io.open(path, "r", encoding="utf-8") as handle:
            text = handle.read()
        if "CamouflageFrame" in text:
            touched.append(os.path.basename(path))
    check(not touched, "D4 本批没有把伪装框架塞进共享的连接判定入口（与「分隔框架」互不干扰）",
          "命中: %s" % touched)

    print("       [推演] RS 的网络连边 / 连接臂外形共用同一句判定")
    print("              （InWorldNetworkNodeContainerImpl#canAcceptIncomingConnection）：")
    print("              「目标方块必须提供网络节点容器」。伪装框架不提供任何节点容器，")
    print("              线缆看它 = 看一块石头 → 连接与网络图与「加这个方块之前」逐位相同。")


# ------------------------------------------------------------------ E 持久化与掉落守恒

def check_persistence_and_drops():
    be_src = code_only(read("copycat_be"))
    checks = [
        ('tag.put("Material"', "E1 外壳材质写入 NBT（BlockState 形式，随存档持久化）"),
        ('tag.put("Item"', "E2 被消耗的外壳物品写入 NBT（用于破坏时归还）"),
        ('tag.getCompound("Material")', "E3 读档时取回外壳材质"),
        ('tag.getCompound("Item")', "E4 读档时取回外壳物品"),
        ("clientPacket", "E5 同一份读写同时用于存档与客户端同步（clientPacket 区分）"),
    ]
    for token, why in checks:
        check(token in be_src, why)

    block_src = code_only(read("copycat_block"))
    check("Block.popResource(pLevel, pPos, ufte.getConsumedItem())" in block_src,
          "E6 破坏 / 被替换时把外壳物品原样掉回（onRemove）")
    check("player.isCreative()" in block_src and "setConsumedItem(ItemStack.EMPTY)" in block_src,
          "E7 创造模式破坏时不掉外壳（防止凭空产出）")
    check("copyWithCount(1)" in be_src, "E8 记录外壳物品时只留 1 个（堆叠数量不会被放大）")

    loot = load_json(RESOURCE["loot"])
    rolls = [p.get("rolls") for p in loot["pools"]]
    check(rolls == [1.0], "E9 战利品表恰好 1 次投掷（框架本体不多掉）", str(rolls))

    print("       [守恒推演] 放置：消耗 1 个伪装框架 + 1 个外壳方块；")
    print("                  破坏：战利品表掉 1 个伪装框架 + onRemove 掉 1 个外壳方块 → 收支相抵；")
    print("                  创造模式：playerWillDestroy 先清空外壳记录 → 不掉外壳，也不会复制；")
    print("                  换外壳：先用扳手拆下（IWrenchable 把旧外壳还给玩家）才能装新的。")
    be_read = read("copycat_be")
    if "getAcceptedBlockState" in be_read:
        print("       [已知边界] Create 的 read() 在校验失败（存档里的材质按现行规则已不被接受）时会把")
        print("                  材质与被消耗物品一并清空——这是 Create 伪装板原本的行为，本模组刻意保持一致。")


# ------------------------------------------------------------------ F 只能装在管道 / 线缆上

def check_pipe_only():
    """用户要求：伪装框架「只能放在线缆 / 流体管道上」（**所有**线缆类与流体管道类），装在别处不得放置、只给提示。"""
    item_src = code_only(read("item"))
    check("SeparationFrameGuard.isSheathable" in item_src,
          "F1 用共用的「可裹方块」判定（与分隔框架同一入口，口径不会漂移）")
    # F2（2026-09-25 第 3 次反馈修正的真因）：上一版判定通过后把交互交给 BlockItem#place，
    # 而线缆不可被替换 → 原版的放置目标恒为「命中格 + 命中面」= 线缆旁边那一格空气：
    # 玩家看到「方块被放到了管道 / 线缆上面」，线缆本身毫无变化，于是三轮反馈都是「套不上去」。
    # 现在物品<b>不再放置任何方块</b>，改成与分隔框架完全一致的「坐标标记 + 外面画一层外壳」。
    check("super.useOn" not in item_src and "public InteractionResult place(" not in item_src,
          "F2 物品不再放置方块（没有 super.useOn / place 覆写）—— 「放到旁边一格」这条无效路径已删除")
    check("RsccCamouflage.add(" in item_src and "RsccCamouflage.remove(" in item_src,
          "F2b 两个分支就是「裹上（写坐标标记）/ 取下」")
    check("UseOnContext" in item_src and "context.getClickedPos()" in item_src
          and "BlockPlaceContext" not in item_src,
          "F2c 判定读的是 UseOnContext#getClickedPos（玩家真正点到的那一格）",
          "含 UseOnContext=%s / 含 BlockPlaceContext=%s"
          % ("UseOnContext" in item_src, "BlockPlaceContext" in item_src))
    check(item_src.count("InteractionResult.FAIL") == 3,
          "F2d 只有三处 FAIL（传动杆不支持套伪装 / 非管道 · 非线缆；无建造权限）"
          "→ 对着线缆 / 管道 / 传动杆时都不再有「静默吞掉」的路径",
          "FAIL 出现次数=%d" % item_src.count("InteractionResult.FAIL"))
    check("InteractionResult.FAIL" in item_src and "isSheathable" in item_src,
          "F3 非管道 / 非线缆 → FAIL：不放置、不消费")
    check("displayClientMessage" in item_src and "!level.isClientSide()" in item_src,
          "F4 提示只在服务端下发（动作栏），不会双端各说一次")
    check("getClickedPos()" in item_src,
          "F5 判据是「玩家点到的那一格」（对着管道右键才能裹），不是将要放置的那一格")

    guard_src = code_only(read("guard"))
    check("RsccWireBlocks.isWire(state) || isFluidPipe(state)" in guard_src,
          "F6 共用判定的口径 = RS 线缆族 + Create 流体管道族（两个框架与连锁共用这一句）")

    # F7~F10（2026-09-25 本轮修正）：判定必须是「类别判定」，且覆盖**所有**同族变体
    # —— 用户实测：伪装框架「套不上线缆」，根因就是判定写成了一份很窄的白名单。
    wires = os.path.join(PKG, "support", "RsccWireBlocks.java")
    wire_src = code_only(read_text(wires))
    for cls, why in (("CableBlock", "RS 线缆（16 色共用一个方块类）"),
                     ("ExporterBlock", "RS 输出总线"), ("ImporterBlock", "RS 输入总线")):
        check("instanceof %s" % cls in wire_src, "F7 线缆族按类判定覆盖 %s（%s）" % (cls, why))
    for cls, why in (("EncasedPipeBlock", "装壳后的管道"),
                     ("AxisPipeBlock", "玻璃流体管道（直管族）"),
                     ("SmartFluidPipeBlock", "智能流体管道")):
        check("instanceof %s" % cls in guard_src, "F8 管道族按类判定覆盖 %s（%s）" % (cls, why))
    check("FluidPipeBlock.isPipe" in guard_src,
          "F8b 普通管道复用 Create 自己的 isPipe（instanceof → 全部子类自动覆盖）")
    for token in ("ResourceLocation", "BuiltInRegistries", "Blocks.INSTANCE"):
        check(token not in guard_src and token not in wire_src,
              "F9 判定里没有方块 id / 注册表清单（未出现 %s）→ 不是「只能放在某几种上面」" % token)

    # F10 覆盖清单推演（与「分隔框架」自检同一张表，两处结论必须一致）
    family = {"CableBlock": True, "ImporterBlock": True, "ExporterBlock": True,
              "FluidPipeBlock": True, "EncasedPipeBlock": True, "AxisPipeBlock": True,
              "SmartFluidPipeBlock": True, "PumpBlock": False, "None": False}

    def sheathable(block_class):
        return family[block_class]

    table = [("RS 线缆（任一颜色）", "CableBlock", True),
             ("RS 输入总线", "ImporterBlock", True),
             ("RS 输出总线", "ExporterBlock", True),
             ("流体管道（普通）", "FluidPipeBlock", True),
             ("装壳后的管道", "EncasedPipeBlock", True),
             ("玻璃流体管道", "AxisPipeBlock", True),
             ("智能流体管道", "SmartFluidPipeBlock", True),
             ("动力泵（机器）", "PumpBlock", False),
             ("石头 / 泥土 / 机器", "None", False)]
    bad = [label for label, cls, expected in table if sheathable(cls) != expected]
    for label, cls, expected in table:
        print("       %-20s -> %s" % (label, "可套" if sheathable(cls) else "不套"))
    check(not bad, "F10 覆盖推演：所有线缆类 / 流体管道类都能套，机器与普通方块一律不套",
          "异常项 %s" % bad)

    main_src = read("main")
    check("item.CamouflageFrameItem(" in main_src,
          "F11 注册的物品就是带约束的实现（不是裸的 BaseBlockItem）")

    print("       [约束推演] 对着石头 / 泥土 / 机器右键 → isSheathable=false → FAIL + 一句提示；")
    print("                  对着线缆 / 流体管道（含玻璃管 / 装壳管 / 智能管）右键 → 照旧能装。")


def main():
    print("=" * 100)
    print("伪装框架自检：%s" % BLOCK)
    print("=" * 100)
    check_registration_and_resources()
    check_recipe_and_loot()
    print("-" * 100)
    check_full_block_only()
    print("-" * 100)
    check_appearance_rendering()
    print("-" * 100)
    check_wire_coexistence()
    print("-" * 100)
    check_persistence_and_drops()
    print("-" * 100)
    check_pipe_only()
    print("=" * 100)
    print("检查项: %d；问题: %d" % (CHECKS[0], len(PROBLEMS)))
    if PROBLEMS:
        for item in PROBLEMS:
            print("  [FAIL] %s" % item)
        return 1
    print("[result] 自检通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
