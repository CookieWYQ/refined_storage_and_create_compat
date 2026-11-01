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
# 缓存根统一跟随 GRADLE_USER_HOME（本机 = D:\gradle\caches，Gradle 真正在用的那个）；
# 以前这里写死 ~/.gradle/caches，两个根里的 MC 编译产物不是同一批 parchment 映射 ⇒ 取证错位。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _gradle_cache as gc  # noqa: E402  （必须在 sys.path 调整之后再导入）

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
print("⑦ 物理化「单格」：Sable 那张专用渲染分支的取证 + 本轮修法（模型数据通道）")
print("=" * 100)
# 用户原话：「线缆物理化之后的那个伪装框架，单个（格）的时候仍然是没有正常渲染」。
# 取证结论（全部来自 jar 字节码，可复现）：
#   ① Sable 对「结构里只有一格」有一个**专用渲染分支**（VanillaSingleSubLevelRenderData +
#      SingleBlockSubLevelWrapper），它不走常规 SectionCompiler；
#   ② 常规区块 / 多格子关卡走 SectionCompiler，而 SectionCompiler 里**明确调用**
#      BakedModel.getModelData(world, pos, state, base)；
#   ③ 单格分支把模型数据直接取自 ClientLevel.getModelData(pos)（NeoForge 的惰性缓存）
#      与 SingleBlockSubLevelWrapper.getModelData(pos)（默认实现 = ModelData.EMPTY），
#      **从不调用** BakedModel.getModelData ⇒ 只靠 gatherModelData 取数的写法在单格路径下必然零外壳。
#   ④ 本轮修法：让「裹着什么」搭上**方块实体模型数据**这条所有渲染路径都会读的通道
#      （ClientLevel.getModelData 并上附件；缺省 getter 直接问方块实体；外壳模型在模型数据里
#      已经带着伪装记录时直接用、getRenderTypes 缺阶段化字段时按材质现算）。详见 ⑧。
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
    # MC 编译产物：多份候选（不同 parchment 映射批次）时取 mtime 最新，并打印选的是哪一份
    # （原先取 glob[0]，顺序由目录枚举决定 ⇒ 换台机器/换目录布局就可能变）。
    _mc_jar, _mc_notes = gc.find_mc_jar()
    for _line in _mc_notes:
        print("  " + _line.strip())
    if _mc_jar:
        import zipfile as _zf
        with _zf.ZipFile(_mc_jar) as _mc:
            _section = _mc.read("net/minecraft/client/renderer/chunk/SectionCompiler.class")
        check(MARKER_GET_MODEL_DATA in _section,
              "⑦4 对照：常规区块 / 多格子关卡走 SectionCompiler，而它<b>明确调用</b> "
              "BakedModel.getModelData —— 这正是「多格正常、单格不行」的分界（同一条取数入口）")
    else:
        print("  [NOTE] 找不到 compiledWithNeoForge_*.jar（Gradle 缓存缺失）⇒ 跳过 ⑦4 的对照取证")
    check(not hard_refs,
          "⑦5 本工程零硬引用 Sable / Simulated / 航空学（⑤10 同一判据）⇒ 修法必须留在本工程内，"
          "靠的是「模型数据通道」而不是引用 Sable 的内部类")
    gather_now = body(SHELL_CODE, "protected ModelData.Builder gatherModelData(")
    check("CamouflageModelData.camoOf(blockEntityData)" in gather_now
          and gather_now.index("CamouflageModelData.camoOf(blockEntityData)")
          < gather_now.index("RsccCamouflage.get(world, pos)"),
          "⑦6 单格路径的取数已由「模型数据通道」补齐：gatherModelData 先读引擎递进来的方块实体模型数据"
          "（物理化单格那条路径把 ClientLevel.getModelData 直接交给 getQuads，因此这条通道拿得到），"
          "读不到才回落去问世界里的方块实体 —— 单格路径不再依赖那次「不会被调用」的回调")
    check("shellLayers(camo, rand, data)" in body(SHELL_CODE, "public ChunkRenderTypeSet getRenderTypes(")
          and "modelOf(material).getRenderTypes(material, rand, wrappedData(data))"
          in body(SHELL_CODE, "private ChunkRenderTypeSet shellLayers("),
          "⑦7 渲染层也补上了单格路径：模型数据里没有阶段化的 SHELL_LAYERS 时，按<b>材质自己的模型</b>现算"
          "（只查烘焙模型表、不读世界 / 坐标）—— 否则引擎不会为材质所在的层调用 getQuads，"
          "外壳仍然一个四元组都产不出来")

# ============================================================ ⑧ 材质数据通道（本轮渲染修复）
print()
print("=" * 100)
print("⑧ 材质数据通道：写 = 数据附件（setData ⇒ setChanged + syncData），读 = 同一份附件的模型数据投影")
print("=" * 100)
MODEL_DATA = java("client", "model", "CamouflageModelData.java")
MODEL_DATA_CODE = code_only(MODEL_DATA)
CLIENT_LEVEL_MIXIN = java("mixin", "client", "CamouflageClientLevelModelDataMixin.java")
CLIENT_LEVEL_MIXIN_CODE = code_only(CLIENT_LEVEL_MIXIN)
GETTER_MIXIN = java("mixin", "client", "CamouflageGetterModelDataMixin.java")
GETTER_MIXIN_CODE = code_only(GETTER_MIXIN)
MIXINS_CFG = read_text(os.path.join(ROOT, "src", "main", "resources", "rs_create_compat.mixins.json"))
INTERACTION_CODE = code_only(INTERACTION)


def read_zip_text(jar, entry):
    """从 jar 里直接读一个文本条目（不落盘：C 盘只剩几 GB，绝不整包解压）。"""
    import zipfile
    with zipfile.ZipFile(jar) as zf:
        return zf.read(entry).decode("utf-8", "replace")


def newest(pattern):
    hits = glob.glob(pattern)
    return max(hits, key=os.path.getmtime) if hits else None


# ⑧1 只有一份「伪装记录」属性：外壳模型里的 CAMOUFLAGE_PROPERTY 必须是同一个实例的别名
check("public static final ModelProperty<RsccCamouflage.Camo> CAMOUFLAGE" in MODEL_DATA_CODE
      and "ModelData.of(CAMOUFLAGE, camo)" in MODEL_DATA_CODE
      and re.sub(r"\s+", "", "CAMOUFLAGE_PROPERTY=CamouflageModelData.CAMOUFLAGE;")
      in re.sub(r"\s+", "", SHELL_CODE),
      "⑧1 模型数据属性只有一个实例：CamouflageShellModel.CAMOUFLAGE_PROPERTY = "
      "CamouflageModelData.CAMOUFLAGE（两条到达模型的通道必须读同一个键，否则等于两份状态）")

# ⑧2 取数顺序：先「引擎递进来的方块实体模型数据」，读不到才回落问世界
gather_now = body(SHELL_CODE, "protected ModelData.Builder gatherModelData(")
check("CamouflageModelData.camoOf(blockEntityData)" in gather_now
      and gather_now.index("CamouflageModelData.camoOf(blockEntityData)")
      < gather_now.index("RsccCamouflage.get(world, pos)"),
      "⑧2 gatherModelData 先读方块实体模型数据（物理化单格那条路径唯一拿得到的通道），"
      "读不到才回落 RsccCamouflage.get(world, pos)（区块 / 装置烘焙的实际来源）")

# ⑧3 getQuads：BASE_DATA_PROPERTY 缺席时把 data 原样转交给被裹模型（否则线缆会丢连接臂）
quads_now = body(SHELL_CODE, "public List<BakedQuad> getQuads(")
check("base != null ? base :" in re.sub(r"\s+", " ", quads_now)
      and "originalModel.getQuads(state, side, rand, forwarded, renderType)" in quads_now,
      "⑧3 getQuads 在「没有阶段化 BASE_DATA_PROPERTY」时把整份 data 转交给被裹方块自己的模型"
      "（物理化单格路径下 RS 线缆的 CABLE_CONNECTIONS 就在 data 里，转发丢了就只剩光秃秃的芯柱）")

# ⑧4 getRenderTypes：缺 SHELL_LAYERS 时按材质现算，绝不退回「框架层」
check("shellLayers(camo, rand, data)" in body(SHELL_CODE, "public ChunkRenderTypeSet getRenderTypes(")
      and "modelOf(material).getRenderTypes(material, rand, wrappedData(data))"
      in body(SHELL_CODE, "private ChunkRenderTypeSet shellLayers("),
      "⑧4 渲染层缺字段时按材质自己的模型现算（纯模型查表，不读世界 / 坐标）："
      "材质在 solid / translucent 层时，引擎才会为那一层调用 getQuads")

# ⑧5 四元组阶段仍然画「材质自己的模型」（与 Create 伪装板同一句）
check("model.getQuads(material, side, rand, wrapped, renderType)"
      in body(SHELL_CODE, "private List<BakedQuad> shellQuads("),
      "⑧5 外壳四元组 = 材质方块自己的模型（与 Create 伪装板同一句 getModelOf(material).getQuads）")

# ⑧6 客户端模型数据：ClientLevel#getModelData 的返回处并上附件
check("@Mixin(ClientLevel.class)" in CLIENT_LEVEL_MIXIN
      and 'method = "getModelData(Lnet/minecraft/core/BlockPos;)' in CLIENT_LEVEL_MIXIN
      and "getExistingDataOrNull(RsccCamouflageAttachment.CAMOUFLAGE.get())" in CLIENT_LEVEL_MIXIN_CODE
      and "CamouflageModelData.withCamo(cir.getReturnValue(), camo)" in CLIENT_LEVEL_MIXIN_CODE,
      "⑧6 ClientLevel#getModelData 的返回值并上同一份附件（物理化单格把它的返回值直接交给 getQuads）")

# ⑧7 缺省 getter：没有自己覆写 getModelData 的 BlockAndTintGetter 直接问方块实体
check("@Mixin(BlockAndTintGetter.class)" in GETTER_MIXIN
      and "default ModelData getModelData(final BlockPos pos)" in GETTER_MIXIN
      and "getBlockEntity(pos)" in GETTER_MIXIN_CODE
      and "CamouflageModelData.of(camo)" in GETTER_MIXIN_CODE,
      "⑧7 没有覆写 getModelData 的 BlockAndTintGetter（物理化单格 wrapper / 第三方包装）"
      "默认直接问 getBlockEntity(pos) 的附件 —— 与 ⑧6 是同一份数据")

# ⑧8 两个 mixin 只登记在 client 段（专服不加载）
import json as _json
_mixins = _json.loads(MIXINS_CFG)
check("client.CamouflageClientLevelModelDataMixin" in _mixins.get("client", [])
      and "client.CamouflageGetterModelDataMixin" in _mixins.get("client", [])
      and "client.CamouflageClientLevelModelDataMixin" not in _mixins.get("mixins", [])
      and "client.CamouflageGetterModelDataMixin" not in _mixins.get("mixins", []),
      "⑧8 两个新 Mixin 只登记在 mixins.json 的 client 段（专用服务端不加载，避免引用客户端类型）")

# ⑧9 写路径 = write(...) 两步：setData（附件同步）+ notifyClient（请重烘）
write_now = body(CAMOU_CODE, "private static void write(")
check("be.setData(RsccCamouflageAttachment.CAMOUFLAGE.get(), camo)" in write_now
      and "notifyClient(level, List.of(be.getBlockPos()))" in write_now,
      "⑧9 所有单格改动只经 write(...)：先 setData（附件写 + NeoForge 同步），再 notifyClient（重烘）")

# ⑧10 源码级证明「写即同步」：BlockEntity.setData ⇒ setChanged() + super.setData（⇒ syncData ⇒
#     AttachmentSync.syncBlockEntityUpdate 下发）。两份源码都从缓存 jar 里直接读，不落盘。
_mc_src = newest(os.path.join(gc.cache_root()[0], "neoformruntime", "intermediate_results",
                              "sourcesAndCompiledWithNeoForge_*_output.jar"))
_ng_src, _ng_notes = gc.find_jar("net.neoforged", "neoforge", kind="sources")
try:
    _be_src = read_zip_text(_mc_src, "net/minecraft/world/level/block/entity/BlockEntity.java")
    _set_data = body(_be_src, "public final <T> T setData(")
    _sync_data = body(_be_src, "public final void syncData(")
    _holder_src = read_zip_text(_ng_src, "net/neoforged/neoforge/attachment/AttachmentHolder.java")
    _holder_set = body(_holder_src, "public <T> @Nullable T setData(")
    check("setChanged();" in _set_data and "super.setData(type, data);" in _set_data
          and "AttachmentSync.syncBlockEntityUpdate(this, type);" in _sync_data
          and "syncData(type);" in _holder_set,
          "⑧10 源码级：BlockEntity#setData = setChanged() + super.setData(...)，而 AttachmentHolder#setData "
          "会调 syncData(type)，BlockEntity#syncData 直接走 AttachmentSync.syncBlockEntityUpdate "
          "⇒ 「写附件」这一步天然带存档脏标记 + 下发观察者（不需要我们再补 setChanged / 手写同步包）")
except (OSError, KeyError, TypeError, ValueError, SystemExit) as _exc:  # pragma: no cover - 缓存缺失时显式判负
    check(False, "⑧10 源码级：BlockEntity#setData ⇒ setChanged + syncData（取证失败：%r）" % (_exc,))

# ⑧11 渲染侧没有第二份状态（不许回到「坐标 → 材质」表 / 客户端镜像 / 复制 CopycatBlockEntity）
_render_side = CLIENT_LEVEL_MIXIN_CODE + GETTER_MIXIN_CODE + MODEL_DATA_CODE
check(all(banned not in _render_side for banned in
          ("SavedData", "mirrored", "renderCells", "CopycatBlockEntity", "Map<BlockPos")),
      "⑧11 渲染侧没有第二份状态：两个 Mixin / 模型数据只做「附件 → 模型数据」的投影，"
      "不查全局表、不建客户端镜像、不读 CopycatBlockEntity（与 ②1 / ②2 / ③6 同一条纪律）")

# ⑧12 专服安全：这三个客户端类不许被任何 common 类引用
_model_data_refs = []
for _folder, _dirs, _names in os.walk(os.path.join(ROOT, "src", "main", "java")):
    for _name in _names:
        if not _name.endswith(".java"):
            continue
        _path = os.path.join(_folder, _name)
        _text = code_only(read_text(_path))
        if "CamouflageModelData" in _text:
            _rel = os.path.relpath(_path, ROOT).replace("\\", "/")
            if "/client/" not in _rel and "/mixin/client/" not in _rel:
                _model_data_refs.append(_rel)
check(not _model_data_refs,
      "⑧12 只有 client 包 / client 段 Mixin 引用 CamouflageModelData（common 类零引用 ⇒ 专服不会解析到它）",
      "命中: %s" % _model_data_refs)

# ⑧13 单格装壳 / 换壳都落在同一条写路径上（且先写记录、再扣物品）
_apply = body(INTERACTION_CODE, "private static void applyMaterial(")
check("RsccCamouflage.setMaterial(level, pos, material, consumed)" in _apply
      and _apply.index("RsccCamouflage.setMaterial(") < _apply.index("stack.consume(1, player)"),
      "⑧13 手动逐个套壳（换 / 装外壳）走 RsccCamouflage.setMaterial → write → setData + notifyClient，"
      "且先写记录再扣物品（写不进去就绝不扣）")

# ⑧14 反例自证：把「只写不 sync」「只读不写」「没有模型数据通道」的写法喂给判据 ⇒ 必须判负
def judge_write_sync(src):
    """⑧ 的判据：一次材质写入必须同时包含「附件写入」与「重烘通知」两步。"""
    code = code_only(src)
    return "be.setData(" in code and "notifyClient(" in code


def judge_model_channel(src):
    """⑧ 的判据：外壳取数必须能从「方块实体模型数据」这条通道读到，且渲染层能现算。"""
    code = code_only(src)
    return ("camoOf(blockEntityData)" in code
            and "RsccCamouflage.get(world, pos)" in code
            and "shellLayers(" in code)


COUNTER_ONLY_READ = """
private static void write(ServerLevel level, BlockEntity be, Camo camo) {
    be.getExistingDataOrNull(RsccCamouflageAttachment.CAMOUFLAGE.get());
}
"""
COUNTER_WRITE_NO_SYNC = """
private static void write(ServerLevel level, BlockEntity be, Camo camo) {
    be.setData(RsccCamouflageAttachment.CAMOUFLAGE.get(), camo);
}
"""
COUNTER_NO_MODEL_CHANNEL = """
protected ModelData.Builder gatherModelData(Builder builder, BlockAndTintGetter world, BlockPos pos,
        BlockState state, ModelData blockEntityData) {
    Camo camo = RsccCamouflage.get(world, pos);
    if (camo == null) { return builder; }
    builder.with(CAMOUFLAGE_PROPERTY, camo);
    return builder;
}
"""
check(not judge_write_sync(COUNTER_ONLY_READ)
      and not judge_write_sync(COUNTER_WRITE_NO_SYNC)
      and judge_write_sync(CAMOU_CODE)
      and not judge_model_channel(COUNTER_NO_MODEL_CHANNEL)
      and judge_model_channel(SHELL),
      "⑧14 反例自证：判据对「只读不写」「只写不 sync」判负，对真实写入路径判正；"
      "对「只有 gatherModelData 现问世界」的旧外壳模型判负（⑥ 的旧写法），对当前实现判正")

# ============================================================ ⑨ 连锁应用材质
print()
print("=" * 100)
print("⑨ 连锁应用材质：手持方块 + 连锁键 ⇒ 同族伪装格批量换材质（上限 / 去重 / 权限 / 距离 / 逐块容错 / 提示）")
print("=" * 100)
CHAIN = java("support", "RsccCamouflageChain.java")
CHAIN_CODE = code_only(CHAIN)
SHEATH_CODE = code_only(java("support", "RsccSheathChain.java"))
ULTIMINE_CODE = code_only(java("support", "RsccUltimineIntegration.java"))
ULTIMINE_SRC = java("support", "RsccUltimineIntegration.java")

# ⑨1 唯一入口 + 只处理「已经裹着」的格
check("public static int chainApplyMaterial(" in CHAIN_CODE
      and "if (!RsccCamouflage.isCamouflaged(level, anchor))" in CHAIN_CODE
      and "if (!RsccCamouflage.isCamouflaged(level, pos))" in CHAIN_CODE,
      "⑨1 连锁应用材质只有一个入口，且只改「已经是伪装格」的那些（命中格没裹住 ⇒ 返回 0 交回 FTB / 原版）")

# ⑨2 上限：FTB 的 maxBlocks + 手里真的有那么多块（与既有 affordable 同一口径）
check("limit <= 0" in CHAIN_CODE and "targets.size() >= limit" in CHAIN_CODE
      and "targets.size() >= stack.getCount()" in CHAIN_CODE,
      "⑨2 上限沿用 FTB 对这名玩家生效的 maxBlocks，再叠加手持数量（普通模式）；创造模式不设数量上限")

# ⑨3 去重
check("new LinkedHashSet<>()" in CHAIN_CODE and "targets.add(pos)" in CHAIN_CODE,
      "⑨3 去重：LinkedHashSet 保序去重（同一格不会被写两次、扣两次）")

# ⑨4 权限 + 距离
check("player.mayBuild()" in CHAIN_CODE
      and "Component.translatable(MSG_CANNOT_BUILD)" in CHAIN_CODE
      and "RsccBusClipboard.ULTIMINE_MAX_DISTANCE" in CHAIN_CODE
      and "withinReach(player, pos)" in CHAIN_CODE,
      "⑨4 权限与距离：mayBuild（没权限给出提示），距离复用 RsccBusClipboard.ULTIMINE_MAX_DISTANCE（32 格）")

# ⑨5 批量写入：一次事务 + 每格 try/catch + 一次按区块聚合的重烘
_set_all = body(CAMOU_CODE, "public static BulkWrite setMaterialAll(")
check("for (final BlockPos pos : new LinkedHashSet<>(positions))" in _set_all
      and "try {" in _set_all and "catch (final RuntimeException e)" in _set_all
      and "be.setData(RsccCamouflageAttachment.CAMOUFLAGE.get()," in _set_all
      and "notifyClient(level, written)" in _set_all
      and "return new BulkWrite(changed, failed, refunds)" in _set_all,
      "⑨5 setMaterialAll = 一批一次事务：逐格 setData（附件同步）＋逐块 try/catch＋整批只发一次重烘")

# ⑨6 失败不静默 / 守恒（先写后扣、旧外壳原样归还）
check("Component.translatable(MSG_NO_TARGET)" in CHAIN_CODE
      and "Component.translatable(MSG_PARTIAL" in CHAIN_CODE
      and "Component.translatable(MSG_FULL_BLOCK_ONLY)" in CHAIN_CODE
      and CHAIN_CODE.index("RsccCamouflage.setMaterialAll(") < CHAIN_CODE.index("stack.consume(result.changed(), player)")
      and "CamouflageFrameItem.give(player, refund.copy())" in CHAIN_CODE,
      "⑨6 失败一定有提示（没有可改的格 / 部分失败 / 方块不能当外壳 / 没权限），"
      "且守恒：先写记录再扣新材质，旧外壳原样归还")

# ⑨7 与既有连锁的触发区分：剪贴板 → 框架 → 材质，且框架那一支返回 >0 就不再往下走
_chain_branch = body(ULTIMINE_CODE, "private static Object chain(")
check(_chain_branch.index("AllBlocks.CLIPBOARD.isIn(held)")
      < _chain_branch.index("RsccSheathChain.chainSheath(")
      < _chain_branch.index("RsccCamouflageChain.chainApplyMaterial(")
      and re.search(r"if\s*\(\s*framed\s*>\s*0\s*\)\s*\{\s*return\s+framed;", _chain_branch),
      "⑨7 触发区分（顺序即语义）：剪贴板 → 框架（套上 / 取下，>0 直接返回）→ 材质；"
      "拿框架连锁的语义一字未改，拿方块连锁才改材质")

# ⑨8 规划复用既有 BFS（同族口径只有一处）
check("public static List<BlockPos> planTargets(" in SHEATH_CODE
      and "RsccSheathChain.planTargets(" in CHAIN_CODE,
      "⑨8 「哪几格属于同一段管线」复用 RsccSheathChain.planTargets（同族 BFS / 上限口径只有一份）")

# ⑨9 只接受方块物品（材质），且外壳判定复用 Create
check("stack.getItem() instanceof BlockItem" in CHAIN_CODE
      and "getAcceptedBlockState(level, anchor, stack, face)" in CHAIN_CODE,
      "⑨9 手里必须是方块物品，且「能不能当外壳」复用 Create 的 getAcceptedBlockState（不另立口径）")

# ⑨10 文案键齐备（两份语言文件都要有；缺了玩家看到的是原始 key）
_zh = read_text(os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat",
                             "lang", "zh_cn.json"))
_en = read_text(os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat",
                             "lang", "en_us.json"))
for _key in ("message.rs_create_compat.camouflage_chain.none",
             "message.rs_create_compat.camouflage_chain.partial"):
    check(('"%s"' % _key) in _zh and ('"%s"' % _key) in _en,
          "⑨10 文案键在中英两份语言文件里都存在：%s" % _key)

# ⑨11 反例自证：无上限 / 静默失败 / 无逐块容错 的写法 ⇒ 判负
def judge_chain_entry(src):
    """⑨ 的判据（入口）：上限 + 去重 + 权限 + 失败提示 + 交给批量写入。"""
    code = code_only(src)
    required = (
        "targets.size() >= limit",          # 上限（FTB maxBlocks）
        "LinkedHashSet",                    # 去重
        "player.mayBuild()",                # 权限
        "displayClientMessage",             # 失败不静默
        "RsccCamouflage.setMaterialAll(",   # 交给唯一批量写入
    )
    return all(token in code for token in required)


def judge_batch_write(src):
    """⑨ 的判据（批量写入）：逐块容错 + 数据写入 + 一次重烘。"""
    code = code_only(src)
    required = (
        "catch (final RuntimeException",    # 逐块容错
        "be.setData(",                      # 数据写入（同步由 NeoForge 承诺，见 ⑧10）
        "notifyClient(level, written)",     # 整批只发一次重烘
        "getExistingDataOrNull",            # 只改「已经裹着」的格
        "BulkWrite",                        # 把失败格数带回给调用方
    )
    return all(token in code for token in required)


COUNTER_NO_LIMIT = """
public static int chainApplyMaterial(ServerPlayer player, BlockPos anchor) {
    Set<BlockPos> targets = new LinkedHashSet<>();
    if (!player.mayBuild()) { player.displayClientMessage(msg, true); }
    for (BlockPos pos : targets) {
        try { be.setData(CAMOUFLAGE.get(), camo); } catch (final RuntimeException e) { }
    }
    return RsccCamouflage.setMaterialAll(level, targets, material, consumed).changed();
}
"""
COUNTER_SILENT = """
public static int chainApplyMaterial(ServerPlayer player, BlockPos anchor, int limit) {
    Set<BlockPos> targets = new LinkedHashSet<>();
    if (!player.mayBuild()) { return 0; }
    for (BlockPos pos : targets) {
        if (targets.size() >= limit) { break; }
    }
    return RsccCamouflage.setMaterialAll(level, targets, material, consumed).changed();
}
"""
COUNTER_WRITE_ONLY = """
public static BulkWrite setMaterialAll(ServerLevel level, Collection<BlockPos> positions) {
    List<BlockPos> written = new ArrayList<>();
    for (BlockPos pos : positions) {
        BlockEntity be = level.getBlockEntity(pos);
        be.setData(CAMOUFLAGE.get(), camo);
        written.add(pos);
    }
    return new BulkWrite(written.size(), 0, List.of());
}
"""
check(judge_chain_entry(CHAIN_CODE)
      and judge_batch_write(_set_all)
      and not judge_chain_entry(COUNTER_NO_LIMIT)
      and not judge_chain_entry(COUNTER_SILENT)
      and not judge_batch_write(COUNTER_WRITE_ONLY),
      "⑨11 反例自证：判据对「无上限 / 静默失败 / 无逐块容错且不重烘」的写法判负，"
      "对真实的连锁入口与批量写入判正")

# ============================================================ ⑩ 结论
print()
print("=" * 100)
print("⑩ 本轮结论：伪装外观 = 同一份附件的两种投影（区块 / 装置走 gatherModelData，"
      "物理化单格 / 第三方走去重后的模型数据）；连锁应用材质 = 同一批事务（上限 / 去重 / 权限 / 距离 / 容错 / 提示）")
print("=" * 100)

print()
print("=" * 100)
print("共执行 %d 项检查，问题总数: %d" % (CHECKS[0], len(PROBLEMS)))
for entry in PROBLEMS:
    print("  - " + entry)
print("=" * 100)
sys.exit(1 if PROBLEMS else 0)