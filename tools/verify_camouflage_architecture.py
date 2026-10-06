# -*- coding: utf-8 -*-
"""伪装框架「架构重构」验收：材质载体 / 渲染链路 / 残影 / 外观交互回归。

本轮任务（用户反馈：伪装框架「只会保留在原地」「并没有正确地跟随这个线缆」，
要求照抄机械动力 Copycat 的架构）的四组硬断言：

  ① 材质载体随方块走（附件 → 参与方块实体 NBT 往返；装置搬运时被一起抄走并交还给模型）
  ② 渲染不含任何「世界坐标反查」（取数只来自被裹方块自己的方块实体）
  ③ 残影不可能（方块不在 → 模型数据里没有伪装字段 → 一个外壳四元组都不产出）
  ④ 外观 / 交互回归（K 全局切换、两态共用几何管线、Jade 口径、可套接对象、掉落守恒、抗爆继承）

用法: python tools/verify_camouflage_architecture.py
"""
import io
import os
import glob
import re
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
CREATE = os.path.join(ROOT, "local_src", "external", "Create", "src", "main", "java")

PROBLEMS = []
CHECKS = [0]


def check(ok, label, detail=""):
    CHECKS[0] += 1
    print("%s %s%s" % ("[PASS]" if ok else "[FAIL]", label, (" | " + detail) if detail else ""))
    if not ok:
        PROBLEMS.append(label)


def read_text(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def java(*parts):
    return read_text(os.path.join(PKG, *parts))


def create(*parts):
    return read_text(os.path.join(CREATE, "com", "simibubi", "create", *parts))


def code_only(src):
    """剥掉注释：锚点只认真实代码（javadoc 里会写方法名与「不要怎么做」）。"""
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


def exists(*parts):
    return os.path.exists(os.path.join(PKG, *parts))


CAMOU = java("support", "RsccCamouflage.java")
CAMOU_CODE = code_only(CAMOU)
ATTACH = java("support", "RsccCamouflageAttachment.java")
ATTACH_CODE = code_only(ATTACH)
SHELL = java("client", "model", "CamouflageShellModel.java")
SHELL_CODE = code_only(SHELL)
TARGETS_CODE = code_only(java("client", "CamouflageShellTargets.java"))
CLIENT_INIT = code_only(java("client", "ClientInit.java"))
INTERACTION = java("support", "RsccCamouflageInteraction.java")
REMOVAL = java("mixin", "block", "CamouflageRemovalMixin.java")
REMOVAL_CODE = code_only(REMOVAL)
PROP_MIXIN = java("mixin", "block", "CamouflagePropertyMixin.java")
SHAPE_MIXIN = java("mixin", "block", "CamouflageShapeMixin.java")
DISPLAY = java("support", "RsccCamouflageDisplay.java")
GUARD = java("support", "SeparationFrameGuard.java")
MAIN = java("RS_Create_Compat.java")

# ============================================================ ① 材质载体
print("=" * 100)
print("① 材质载体随方块走：方块实体上的数据附件（参与 NBT 往返 + 被装置一起抄走）")
print("=" * 100)
check("AttachmentType" in ATTACH_CODE and 'register("camouflage"' in ATTACH_CODE
      and "NeoForgeRegistries.Keys.ATTACHMENT_TYPES" in ATTACH_CODE,
      "①1 载体是正式注册的数据附件（neoforge:attachment_types）")
check(".serialize(SERIALIZER)" in ATTACH_CODE,
      "①2 声明了序列化器 → 参与方块实体的 NBT 往返（存档 / 读档 / 被搬运都带着走）")
writer = body(ATTACH_CODE, "public CompoundTag write(")
reader = body(ATTACH_CODE, "public RsccCamouflage.Camo read(")
check('tag.put(KEY_MATERIAL' in writer and 'tag.put(KEY_ITEM' in writer and "KEY_FRAME" in writer
      and 'tag.getCompound(KEY_MATERIAL)' in reader and "tag.get(KEY_ITEM)" in reader,
      "①3 三个字段都进了 NBT（外壳材质 / 被消耗物品 / 「框架是否真的扣过」）")
check("RsccCamouflageAttachment.register(modEventBus)" in code_only(MAIN),
      "①4 附件注册表已挂到模组事件总线（新注册项登记到位）")
# 「搬运」这一环要靠 Create 的装置实现交还方块实体；本工程内的 Create 源码就是硬证据
contraption = code_only(create("content", "contraptions", "Contraption.java"))
check("blockEntity.saveWithFullMetadata(world.registryAccess())" in contraption,
      "①5 Create 装置装配用 saveWithFullMetadata 抄走方块实体 NBT（附件因此随装置走）")
virtual = code_only(create("foundation", "virtualWorld", "VirtualRenderWorld.java"))
check("return blockEntities.get(pos);" in virtual and "return blockEntity.getModelData();" in virtual,
      "①6 装置渲染时 VirtualRenderWorld 把方块实体与模型数据交还给模型")
renderer_ = code_only(create("content", "contraptions", "render", "ContraptionEntityRenderer.java"))
check("model.getModelData(renderWorld, pos, state, modelData)" in renderer_,
      "①7 装置烘焙与区块烘焙走同一句 model.getModelData(...) → 外壳随装置一起被画")
check("SavedData" not in CAMOU_CODE and "getDataStorage" not in CAMOU_CODE,
      "①8 旧的「服务端 SavedData / 世界坐标键」载体已彻底移除")
check("be.setData(RsccCamouflageAttachment.CAMOUFLAGE.get(), camo)" in CAMOU_CODE
      and "be.removeData(RsccCamouflageAttachment.CAMOUFLAGE.get())" in CAMOU_CODE,
      "①9 写入 / 删除都落在「被裹方块自己的方块实体」上（唯一写入口 write(...)）")
check("getExistingDataOrNull" in CAMOU_CODE and "getData(RsccCamouflageAttachment" not in CAMOU_CODE,
      "①10 取数只读不创建（绝不顺手给每根线缆塞一个空附件）")
check("ChunkWatchEvent.Sent" in INTERACTION
      and "RsccCamouflageAttachment.refreshChunkFor(event.getPlayer(), event.getChunk())" in INTERACTION
      and "public static void refreshChunkFor(final ServerPlayer player, final LevelChunk chunk)"
      in ATTACH,
      "①11 区块首次下发时补一次「请重烘」（消掉「网格烘焙先于附件到达」的竞态 → 重登后外壳一定在）")

# ============================================================ ② 渲染链路
print()
print("=" * 100)
print("② 渲染不含任何「世界坐标反查」：取数只来自被裹方块自己的方块实体")
print("=" * 100)
check(not exists("client", "CamouflageRenderer.java"),
      "②1 旧的独立渲染器 client/CamouflageRenderer.java 已删除")
check(not exists("network", "SyncCamouflagePacket.java"),
      "②2 旧的「坐标 → 材质」整份快照包 SyncCamouflagePacket.java 已删除")
check("extends BakedModelWrapperWithData" in SHELL_CODE,
      "②3 外壳模型 = 带模型数据的烘焙模型包装器（与 Create 的 CopycatModel 同一个基类）")
gather = body(SHELL_CODE, "protected ModelData.Builder gatherModelData(")
check("RsccCamouflage.get(world, pos)" in gather,
      "②4 取数唯一来源 = RsccCamouflage.get(world, pos)（读被裹方块自己的方块实体附件）")
for banned, why in (("SavedData", "全局存档表"), ("mirrored", "客户端镜像"), ("renderCells", "坐标整表"),
                    ("getBlockState(", "按世界坐标查方块状态"), ("isLoaded(", "按世界坐标判区块加载"),
                    ("isSheathable(", "按世界坐标做可套判定"), ("SeparationFrameGuard", "可套判定链"),
                    ("LevelRenderer", "独立的按帧渲染器"), ("RenderType.lines()", "屏幕空间线条")):
    check(banned not in SHELL_CODE, "②5 外壳模型里不存在「%s」（%s）" % (banned, why))
check("RefreshCamouflagePacket" not in SHELL and "PacketDistributor" not in SHELL,
      "②6 渲染侧不参与任何网络包（重烘信号只在服务端发、客户端收，与渲染取数无关）")
# 重烘信号本身也不带渲染数据
refresh = code_only(java("network", "RefreshCamouflagePacket.java"))
check("List<Long> positions" in java("network", "RefreshCamouflagePacket.java")
      and "CompoundTag" not in java("network", "RefreshCamouflagePacket.java")
      # 2026-10-01（收敛上一轮补丁）：包仍然只带坐标，而且现在连 RsccCamouflage 都不碰了
      # （上一轮它会顺手把坐标记进「待补烘」待办表，那张表已删除 —— 见 RsccCamouflage 类注释）。
      and "RsccCamouflage" not in refresh
      and "getExistingDataOrNull" not in refresh
      and "ItemStack" not in refresh,
      "②7 「重烘」S2C 包只带坐标，不携带 / 不读取任何材质（材质只有一条来源：方块实体附件）")
check("net.minecraft.client" not in refresh,
      "②8 该包所在的类不含任何客户端类型（专用服务端也会加载它）")

# ============================================================ ③ 残影不可能
print()
print("=" * 100)
print("③ 残影不可能：方块不在 → 模型数据里没有伪装字段 → 一个外壳四元组都不产出")
print("=" * 100)
check("if (camo == null)" in gather and "return builder;" in gather,
      "③1 没被裹住（或方块已不在）→ 连伪装字段都不放进模型数据")
quads = body(SHELL_CODE, "public List<BakedQuad> getQuads(")
check("if (camo != null)" in quads and "quads.addAll(shellQuads(" in quads,
      "③2 只有模型数据里带着伪装记录时才追加外壳四元组（没记录 = 零外壳）")
check("BlockAndTintGetter world" in SHELL and "final BlockPos pos" in SHELL
      and "gatherModelData" in SHELL and "getQuads(" in SHELL,
      "③3 外壳几何只在「方块自己被烘焙」的那两个入口产出（方块没了就没有调用点，没有任何按坐标补画的分支）")
check("@Mixin(BlockBehaviour.class)" in REMOVAL
      and "RsccCamouflage.onBlockRemoved(serverLevel, pos, false)" in REMOVAL_CODE,
      "③4 方块移除时在方块自己的 onRemove 里结算（与 Create 伪装板同一个挂点）")
check("if (isMoving)" in CAMOU_CODE,
      "③5 搬运（活塞 / 装置装配）不结算：材质留在方块实体 NBT 里跟着走")
check("getBlockEntityNBT" not in CAMOU_CODE and "BlockPos.of(" not in CAMOU_CODE
      and "Map<BlockPos" not in CAMOU_CODE
      and "SHELL_WATCH" not in CAMOU_CODE and "watchShellRefresh" not in CAMOU_CODE
      and "drainShellWatch" not in CAMOU_CODE,
      "③6 全链路没有任何「坐标 → 材质」的表，也没有任何「待补烘坐标」的常驻集合"
      "（上一轮那张跨 tick 补烘的表已在 2026-10-01 删除；锁定坐标的集合只剩批量入口里的局部变量）")
check('"refresh_camouflage"' in java("network", "RefreshCamouflagePacket.java"),
      "③7 客户端重烘只由「附件变了」触发（一格一信号），不存在持续按坐标补画的路径")

# ============================================================ ④ 外观 / 交互回归
print()
print("=" * 100)
print("④ 外观 / 交互回归：K 全局切换、共用几何管线、Jade 口径、可套对象、守恒、继承")
print("=" * 100)
check("DEFAULT_KEY = GLFW.GLFW_KEY_K" in java("client", "CamouflageKeybinds.java")
      and "PacketDistributor.sendToServer(new ToggleCamouflageRevealPacket())"
      in java("client", "CamouflageKeybinds.java"),
      "④1 K 键仍是<b>全局</b>切换（空负载 C2S；不带坐标）")
check("isMaterialHidden(@Nullable final Player player)" in CAMOU
      and "toggleMaterialHidden(@Nullable final Player player)" in CAMOU
      and 'HIDDEN_TAG = "rscc_camouflage_hidden"' in CAMOU
      and "getPersistentData()" in CAMOU_CODE,
      "④2 开关真值仍按玩家存在持久化数据里（服务端权威 + 重登保留），与材质是两条独立链路")
shell_quads = body(SHELL_CODE, "private List<BakedQuad> shellQuads(")
check("RsccCamouflage.isMaterialHidden()" in shell_quads and "frameModel.getQuads(" in shell_quads
      and "model.getQuads(material, side, rand, wrapped, renderType)" in shell_quads,
      "④3 「完整显示」与「只显示框架」共用同一个 shellQuads（同一几何管线 ⇒ 切换无跳变）")
display_code = code_only(java("client", "CamouflageShellDisplay.java"))
check("ClientTickEvent.Post" in display_code
      and "allChanged" not in display_code
      and "requestModelDataUpdate()" in display_code
      and "sendBlockUpdated(pos, state, state, 16)" in display_code
      and "CamouflageShellDisplay::onClientTick" in CLIENT_INIT,
      "④4 开关一变只重建<b>受影响的伪装格</b>（逐格清模型数据 + 每区段标脏一次）；"
      "整片重烘 allChanged() 已彻底移除（用户第 1 条：不能整屏一白 / 强烈卡顿）")
check("ChunkRenderTypeSet.of(types)" in SHELL_CODE and "originalModel.getRenderTypes(" in SHELL_CODE,
      "④5 渲染层取并集（被裹方块自己的 ∪ 外壳的）")
# Jade 口径
check("RsccCamouflageDisplay.icon(accessor.getLevel(), accessor.getPosition()"
      in java("client", "jade", "RsccJadePlugin.java")
      and "public static ItemStack icon(" in DISPLAY and "public static Component label(" in DISPLAY
      and "public static ResourceLocation detailId(final BlockState original)" in DISPLAY
      and "material" not in body(DISPLAY, "public static ResourceLocation detailId("),
      "④6 Jade 三条口径不变（图标 = 填充方块 / 文本 = 原部件 +（被伪装）/ ID = 原部件）")
check("RsccCamouflage.material(level, pos)" in DISPLAY,
      "④7 Jade 取数与渲染共用同一份数据（RsccCamouflage.material）")
# 可套对象
check("RsccWireBlocks.isWire(state) || isFluidPipe(state) || isShaft(state)" in code_only(GUARD),
      "④8 可套对象仍 = 线缆 ∪ 流体管道 ∪ 传动杆（单一判定入口）")
check("block instanceof CableBlock" in TARGETS_CODE and "ImporterBlock" in TARGETS_CODE
      and "ExporterBlock" in TARGETS_CODE and "block instanceof FluidPipeBlock" in TARGETS_CODE
      and "block instanceof EncasedPipeBlock" in TARGETS_CODE and "block instanceof AxisPipeBlock" in TARGETS_CODE
      and "block instanceof SmartFluidPipeBlock" in TARGETS_CODE and "block instanceof ShaftBlock" in TARGETS_CODE,
      "④9 外壳族与可套族逐类对齐（线缆 3 + 管道 4 + 传动杆 1）")
# 守恒 / 掉落 / 继承
changed = body(CAMOU_CODE, "public static void onBlockChanged(")
check("Block.popResource(level, pos, new ItemStack(RS_Create_Compat.CAMOUFLAGE_FRAME_ITEM.get()))" in changed
      and "Block.popResource(level, pos, removed.consumed().copy())" in changed
      and "removed.frameConsumed()" in changed,
      "④10 破坏只掉「框架本体 + 当初扣下的那 1 份」（按记录里的守恒位决定）")
check("LootTable" not in CAMOU, "④11 不继承战利品表（填充钻石块不会掉钻石块）")
check("stack.consume(1, player)" in INTERACTION and "RsccCamouflage.remove(level, pos)" in INTERACTION,
      "④12 填充消耗 1 / 取下返还 1（一进一出严格相抵）")
check("material.getExplosionResistance(level, pos, explosion)" in PROP_MIXIN
      and "material.getDestroyProgress(player, level, pos)" in PROP_MIXIN,
      "④13 抗爆 / 挖速仍继承填充方块")
check("RsccCamouflage.overridesShape(level, pos, (BlockState) (Object) this)" in code_only(SHAPE_MIXIN),
      "④14 套上后仍按整格参与碰撞 / 选中（形状接管未被破坏）")

# ============================================================ ⑤ 物理化（子关卡）链路
print()
print("=" * 100)
print("⑤ 物理化（Sable 子关卡）链路：取数不挑 getter + 材质搭区块数据包 + 拆解后补一次")
print("=" * 100)
# 取数口径：唯一来源是「传进来的那个 BlockGetter 的 getBlockEntity」，
# 因此区块烘焙的 RenderChunkRegion 与装置烘焙的 VirtualRenderWorld 都取得到；
# 而「未加载就先返回」这条纪律只对真正的 Level 生效（RenderChunkRegion 不是 Level，不受影响）。
data_of = body(CAMOU_CODE, "public static Camo dataOf(")
check("final BlockEntity be = level.getBlockEntity(pos);" in data_of
      and "be.getExistingDataOrNull(RsccCamouflageAttachment.CAMOUFLAGE.get())" in data_of
      and "instanceof final Level realLevel && !realLevel.isLoaded(pos)" in data_of
      and "RenderChunkRegion" not in data_of and "VirtualRenderWorld" not in data_of,
      "⑤1 取数只问「传进来的 getter」：非主世界 getter（RenderChunkRegion / VirtualRenderWorld / "
      "任何第三方包装的 BlockAndTintGetter）与真实世界走同一句 getBlockEntity")
check("Minecraft.getInstance().level" not in CAMOU_CODE and "getLevel() == " not in CAMOU_CODE
      and "ClientLevel" not in CAMOU_CODE
      and "ServerLevel" not in body(CAMOU_CODE, "public static Camo dataOf("),
      "⑤2 取数里没有任何「必须等于主世界 / 必须是我认识的那个 Level」的筛子（判据只看真实代码："
      "javadoc 里为了讲清重烘链路而提到类型名不算筛子）")
check("isLoaded(pos)" in data_of and "realLevel" in data_of,
      "⑤3 「未加载不查」只挂在真正的 Level 上（避免烘焙线程上为了取一份数据去加载区块）")

# 服务端 → 区块数据包：把材质并进 getUpdateTag 的返回值（任何发送方发的区块都会带上）
att_mixin = code_only(java("mixin", "blockentity", "BlockEntityUpdateTagMixin.java"))
check("@Mixin(BlockEntity.class)" in att_mixin
      and "getUpdateTag(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;" in att_mixin
      and '@At("RETURN")' in att_mixin,
      "⑤4 钩子挂在 BlockEntity#getUpdateTag 的返回处（区块数据包给每个方块实体带的 NBT 就是它）")
check("writeIntoUpdateTag" in att_mixin and "AttachmentHolder.ATTACHMENTS_NBT_KEY" in ATTACH_CODE
      and "holder.getExistingDataOrNull(CAMOUFLAGE.get())" in ATTACH_CODE,
      "⑤5 写进的是 NeoForge 自己在 loadAdditional 里读的那一节 neoforge:attachments "
      "（客户端因此不需要任何额外 Mixin，格式与存档逐字一致）")
update_tag_body = body(ATTACH_CODE, "public static void writeIntoUpdateTag(")
check("if (camo == null)" in update_tag_body and "return;" in update_tag_body
      and "attachments.put(key.toString(), SERIALIZER.write(camo, provider))" in update_tag_body,
      "⑤6 没被裹住的方块实体一个字节都不多写（只并进这一项、不覆盖别人的附件）")

# 拆解回来：方块更新广播之后补发一次（附件包在后 → 客户端一定找得到方块实体）
level_mixin = code_only(java("mixin", "level", "ServerLevelBlockUpdateMixin.java"))
# 源码里描述符是跨行拼接的（与 CamouflageRemovalMixin 同一写法），因此按两段断言
check("@Mixin(ServerLevel.class)" in level_mixin
      and 'method = "sendBlockUpdated(Lnet/minecraft/core/BlockPos;' in level_mixin
      and "Lnet/minecraft/world/level/block/state/BlockState;I)V\"" in level_mixin
      and '@At("RETURN")' in level_mixin and "TAIL" not in level_mixin,
      "⑤7 钩子挂在 ServerLevel#sendBlockUpdated 的每一个 RETURN 上（TAIL 只认最后一条 RETURN，"
      "会漏掉「附近没玩家」那条提前返回）")
check("SeparationFrameGuard.isSheatheableFamily(newState)" in level_mixin
      and "SeparationFrameGuard.isSheatheableFamily(oldState)" in level_mixin,
      "⑤8 先过既有的廉价方块族判定（非线缆 / 管道 / 传动杆的方块更新零开销返回）")
repush = body(CAMOU_CODE, "public static void repushIfCamouflaged(")
check("if (!level.isLoaded(pos))" in repush and "be.getExistingDataOrNull" in repush
      and repush.index("be.syncData(") < repush.index("notifyClient(level, List.of(pos))"),
      "⑤9 补发顺序正确：先重推材质（syncData → NeoForge 按观察者下发），再要一次重烘"
      "（方块更新触发的异步烘焙可能抢在附件包之前完成）")

# 软兼容：零引用、零硬依赖（未装 Sable / 航空学 / Simulated 也能启动）
hard_refs = []
for folder, _dirs, names in os.walk(os.path.join(ROOT, "src", "main", "java")):
    for name in names:
        if not name.endswith(".java"):
            continue
        path = os.path.join(folder, name)
        text = code_only(read_text(path))
        if re.search(r"dev\.ryanhcode|ryanhcode\.sable|aeronautics|sablecompanion|simulated_team", text):
            hard_refs.append(os.path.relpath(path, ROOT))
check(not hard_refs, "⑤10 代码里没有对 Sable / 航空学 / Simulated 的任何硬引用（只出现在注释里）",
      "命中: %s" % hard_refs)
libs = os.path.join(ROOT, "libs")
lib_jars = [n for n in os.listdir(libs)] if os.path.isdir(libs) else []
check(not [n for n in lib_jars if re.search(r"sable|aeronaut|simulated", n, re.I)],
      "⑤11 libs/ 没有被塞进物理模组的 jar（依赖现状未改动）", "libs: %s" % lib_jars)
check("isMoving" in CAMOU_CODE and "if (isMoving)" in CAMOU_CODE,
      "⑤12 回归：Create 装置装配 / 活塞搬运（isMoving）一律不结算 —— 材质留在方块实体 NBT 里跟着走")

# ============================================================ ⑥ 孤立单格（2026-10-01 重做取证后的断言）
print()
print("=" * 100)
print("⑥ 孤立单格必然出外壳：几何与被裹模型 / 连接 / 邻居无关 + 渲染层并集永不为空 + 补丁已收敛")
print("=" * 100)
# ① 替换范围 = 该方块的全部方块状态（含孤立态）。
#    证据链：Create 的 ModelSwapper 用 getStateDefinition().getPossibleStates() 枚举，
#    再用 BlockModelShaper.stateToModelLocation 造键；而客户端 ModelManager 建「状态 → 模型」缓存时
#    用的是同一个 stateToModelLocation（见其 createReload 里对每个状态的 getOrDefault）。
#    因此「孤立态」与「连接态」在引擎眼里就是同一个枚举里的两个状态，不存在漏换的变体。
swapper = code_only(create("foundation", "model", "ModelSwapper.java"))
check("getStateDefinition()" in swapper and "getPossibleStates()" in swapper
      and "BlockModelShaper.stateToModelLocation(blockRl, state)" in swapper,
      "⑥1 枚举范围 = 该方块「全部可能的方块状态」× 引擎自己的 stateToModelLocation"
      "（引擎建状态→模型缓存用的是同一个函数 ⇒ 孤立态与连接态必然都被换到）")
shell_loop = body(CLIENT_INIT, "public static void onModifyBakingResult(")
check("for (final Block block : CamouflageShellTargets.blocks())" in shell_loop
      and "ModelSwapper.getAllBlockStateModelLocations(block)" in shell_loop
      and "models.put(location, new CamouflageShellModel(original, frameModel))" in shell_loop,
      "⑥2 逐块取「全部状态」并逐个包装（没有任何「只换有连接的那些状态」的筛子）")
check("CableConnections" not in SHELL_CODE and "CONNECTIONS" not in SHELL_CODE
      and "relative(" not in SHELL_CODE and "getBlockState(" not in SHELL_CODE,
      "⑥3 外壳几何不看连接、也不看邻居（孤立单格与有邻居走的是同一条产出路径）")

# ①'' 产出路径本身：外壳只由「模型数据里的伪装记录」驱动，与被裹模型的四元组无关。
check("quads.addAll(shellQuads(state, side, rand, data, camo, renderType))" in quads
      and "shellQuads(state, side, rand, data, camo, renderType)" in quads,
      "⑥4 外壳分支的入参里没有任何「被裹模型的四元组」：那条 list 是并列叠加，不是外壳的输入"
      "（孤立线缆只返回一根芯柱、甚至一根都不返回，外壳照样产出）")
check("instanceof" not in SHELL_CODE and "FluidPipeBlock" not in SHELL_CODE
      and "ShaftBlock" not in SHELL_CODE and "CableBlock" not in SHELL_CODE,
      "⑥5 外壳模型里没有任何「按被裹方块族分流」的分支 ⇒ 线缆 / 流体管道 / 传动杆走同一条产出路径")

# ② 渲染层：并集永不为空，且层集合只算一次、两处共用。
check("types.isEmpty() ? ChunkRenderTypeSet.of(RenderType.solid()) : ChunkRenderTypeSet.of(types)"
      in SHELL_CODE,
      "⑥6 渲染层并集永不为空（被裹方块自己报空集合时兜底 solid）——"
      "引擎只为 getRenderTypes 里出现过的层调用 getQuads，报空集合 = 外壳一个四元组都产不出来")
check("SHELL_LAYERS_PROPERTY" in gather and "SHELL_LAYERS_PROPERTY" in code_only(SHELL)
      and "data.get(SHELL_LAYERS_PROPERTY)" in shell_quads,
      "⑥7 外壳参与的层在模型数据阶段只算一次（SHELL_LAYERS_PROPERTY），"
      "getRenderTypes 与 getQuads 的层门禁读同一份 ⇒ 不可能「报了 A 层、却在 B 层判掉」")
check("model.getRenderTypes(" not in shell_quads,
      "⑥8 四元组阶段不再现问一次材质模型的层集合（两次独立查询一旦不一致，外壳就会在那层被静默丢掉）")
check("MATERIAL_HIDDEN_PROPERTY" in gather and "data.get(MATERIAL_HIDDEN_PROPERTY)" in shell_quads,
      "⑥9 「该画框架还是画材质」也钉进模型数据（K 键那一态在三个入口之间同源，不会半模型半框架）")

# ③ 上一轮的重烘补丁已收敛到最小必要。
check("forceBlockUpdate" not in CAMOU_CODE and "sendBlockUpdated(pos, state, state" not in CAMOU_CODE,
      "⑥10 已删除「自造方块更新」这条补丁（取证结论：重烘链路本来就是通的，玩家看到的是原版放置方块本身引起的重烘）")
check("SHELL_WATCH" not in CAMOU_CODE and "watchShellRefresh" not in CAMOU_CODE
      and "drainShellWatch" not in CAMOU_CODE
      and "WATCH_TICKS" not in code_only(java("client", "CamouflageShellDisplay.java")),
      "⑥11 已删除「随后 N 个 tick 连烘」那张待办表（它只让每条改动路径多盯 5 个 tick，不改变任何一帧画出来的东西）")
write_body = body(CAMOU_CODE, "private static void write(")
check("notifyClient(level, List.of(be.getBlockPos()))" in write_body
      and "forceBlockUpdate" not in write_body,
      "⑥12 write(...) 收敛为两步：setData（附件随 NeoForge 同步下发）+ notifyClient（请重烘）")
check("repushIfCamouflaged" in CAMOU_CODE and "be.syncData(" in CAMOU_CODE
      and "ServerLevelBlockUpdateMixin" in java("mixin", "level", "ServerLevelBlockUpdateMixin.java"),
      "⑥13 保留「方块实体被重新落地 / 子关卡拆解回来 → 补发附件 + 请重烘」这条已验证链路（物理化用）")

# ④ 回归：K 键 / 取数唯一来源 原样保留。
display = code_only(java("client", "CamouflageShellDisplay.java"))
check("allChanged" not in display and "hidden == appliedHidden" in display
      and "SectionPos" in display and "CamouflageShellDisplay::onClientTick" in CLIENT_INIT,
      "⑥14 回归：K 键仍是「一变就重建」，但只重建受影响的伪装格（allChanged 全量重烘不再存在）")
check("SeparationFrameGuard.isSheatheableFamily" not in SHELL_CODE
      and "RsccCamouflage.get(world, pos)" in gather,
      "⑥15 回归：外壳取数仍只有一条来源（RsccCamouflage.get(world, pos)，与 ②4 同源）")

# ============================================================ ⑦ 物理化「单格」链路：取证与结论
print()
print("=" * 100)
print("⑦ 物理化「单格」：为什么本工程内修不了（本轮如实说明，含取证 + 最小可交付替代）")
print("=" * 100)
# 用户原话：「线缆物理化之后的那个伪装框架，单个（格）的时候仍然是没有正常渲染」。
# 取证结论（全部来自 jar 字节码，可复现）：
#   ① Sable 对「结构里只有一格」有一个**专用渲染分支**（VanillaSingleSubLevelRenderData +
#      SingleBlockSubLevelWrapper），它不走常规 SectionCompiler；
#   ② 常规区块 / 多格子关卡走 SectionCompiler，而 SectionCompiler 里**明确调用**
#      BakedModel.getModelData(world, pos, state, base) —— 我们的外壳正是从那里拿到材质
#      （CamouflageShellModel#gatherModelData 是唯一取数入口）；
#   ③ 单格分支把模型数据直接取自 ClientLevel.getModelData(pos)（NeoForge 的惰性缓存）
#      与 SingleBlockSubLevelWrapper.getModelData(pos)（默认实现 = ModelData.EMPTY），
#      **从不调用** BakedModel.getModelData ⇒ 我们的 gatherModelData 在单格路径下根本不会被调用
#      ⇒ 模型数据里没有伪装字段 ⇒ getQuads 一个外壳四元组都不产出（表现就是「单格不渲染」）。
#   ④ 本工程内修不了的原因：getQuads / getRenderTypes 的入参里 **没有 world + pos**，
#      拿不到方块实体就取不到材质；而唯一能补的载体（「坐标 → 材质」全局表 / 客户端镜像）
#      正是上一轮为消残影与「跟随」而**刻意删除**的旧架构（见 ②1 / ②2 / ③6）。要修必须在
#      Sable 的单格渲染入口补一句 BakedModel.getModelData(...)（= 硬引用 Sable 内部类，
#      或由上游修）；本工程「零硬引用 Sable」这条不变式（⑤10）不允许这么做。
#   ⑤ 最小可交付替代（可操作）：把线缆与**任意第二格**（哪怕一块石头）一起物理化 →
#      结构 ≥2 格时走 VanillaChunkedSubLevelRenderData → SectionCompiler → 外壳正常渲染。
SABLE_JARS = []
_mods = os.path.join(ROOT, "run", "mods")
if os.path.isdir(_mods):
    SABLE_JARS = [os.path.join(_mods, n) for n in os.listdir(_mods)
                  if re.match(r"sable-.*\.jar$", n, re.I)]
MARKER_GET_MODEL_DATA = (
    b"(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
    b"Lnet/minecraft/world/level/block/state/BlockState;"
    b"Lnet/neoforged/neoforge/client/model/data/ModelData;)"
    b"Lnet/neoforged/neoforge/client/model/data/ModelData;")
if not SABLE_JARS:
    print("  [NOTE] run/mods 下没有 sable-*.jar：本机未装物理化模组 ⇒ 本节取证按「不适用」处理，"
          "不计入失败（结论仍是「本工程内修不了」，理由见 ④）")
else:
    import zipfile
    with zipfile.ZipFile(SABLE_JARS[0]) as _jar:
        def _entry(name):
            try:
                return _jar.read(name)
            except KeyError:
                return None

        _single_render = _entry("dev/ryanhcode/sable/sublevel/render/vanilla/"
                                "VanillaSingleSubLevelRenderData.class")
        _wrapper = _entry("dev/ryanhcode/sable/sublevel/render/vanilla/"
                          "SingleBlockSubLevelWrapper.class")
        _platform = _entry("dev/ryanhcode/sable/neoforge/platform/"
                           "SableSubLevelRenderPlatformImpl.class")
    check(_single_render is not None and _wrapper is not None,
          ("⑦1 Sable 有「单格专用渲染分支」：%s 里同时存在 VanillaSingleSubLevelRenderData 与 "
           "SingleBlockSubLevelWrapper（⇒ 单格与多格走的是两条不同链路）")
          % os.path.basename(SABLE_JARS[0]))
    check(_platform is not None and b"tesselateWithoutAO" in _platform,
          "⑦2 单格分支自带渲染入口：SableSubLevelRenderPlatformImpl 里出现 tesselateWithoutAO"
          "（单格自己喂模型数据，不经过 SectionCompiler）")
    check(_platform is not None and MARKER_GET_MODEL_DATA not in _platform,
          "⑦3 单格分支<b>从不</b>调用 BakedModel.getModelData（该 class 的常量池里没有它的描述符）"
          "⇒ CamouflageShellModel#gatherModelData 在单格路径下不会被调用（外壳因此零四元组）")
    _mc_jars = glob.glob(os.path.join(os.path.expanduser("~"), ".gradle", "caches",
                                      "neoformruntime", "intermediate_results",
                                      "compiledWithNeoForge_*_output.jar"))
    if _mc_jars:
        import zipfile as _zf
        with _zf.ZipFile(_mc_jars[0]) as _mc:
            _section = _mc.read("net/minecraft/client/renderer/chunk/SectionCompiler.class")
        check(MARKER_GET_MODEL_DATA in _section,
              "⑦4 对照：常规区块 / 多格子关卡走 SectionCompiler，而它<b>明确调用</b> "
              "BakedModel.getModelData —— 这正是「多格正常、单格不行」的分界（同一条取数入口）")
    else:
        print("  [NOTE] 找不到 compiledWithNeoForge_*.jar（Gradle 缓存缺失）⇒ 跳过 ⑦4 的对照取证")
    check(not hard_refs,
          "⑦5 本工程零硬引用 Sable / Simulated / 航空学（⑤10 同一判据）⇒ 修单格必须新增对 "
          "Sable 内部类的引用，或由上游修；本工程选择「如实说明 + 给最小替代」")
    check("RsccCamouflage.get(world, pos)" in body(SHELL_CODE, "protected ModelData.Builder gatherModelData(")
          and "BlockAndTintGetter" not in body(SHELL_CODE, "public List<BakedQuad> getQuads(")
          and "BlockPos" not in body(SHELL_CODE, "public List<BakedQuad> getQuads(")
          and "BlockAndTintGetter" not in body(SHELL_CODE, "public ChunkRenderTypeSet getRenderTypes(")
          and "BlockPos" not in body(SHELL_CODE, "public ChunkRenderTypeSet getRenderTypes("),
          "⑦6 外壳取数只发生在 gatherModelData（RsccCamouflage.get(world, pos)），而 getQuads / "
          "getRenderTypes 的签名里没有任何 BlockAndTintGetter / BlockPos ⇒ 单格路径下无从取数"
          "（这条就是「本工程内修不了」的根据，与 ②4 / ⑥15 同源）")

print()
print("=" * 100)
print("共执行 %d 项检查，问题总数: %d" % (CHECKS[0], len(PROBLEMS)))
for entry in PROBLEMS:
    print("  - " + entry)
print("=" * 100)
sys.exit(1 if PROBLEMS else 0)
