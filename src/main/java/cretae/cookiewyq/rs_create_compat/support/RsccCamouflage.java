package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.CamouflageFrameItem;
import cretae.cookiewyq.rs_create_compat.network.RefreshCamouflagePacket;
import cretae.cookiewyq.rs_create_compat.network.SyncCamouflageRevealPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「伪装框架」的唯一状态中心。
 *
 * <h2>载体：材质挂在<b>被裹方块自己的方块实体</b>上（本轮架构重构，照抄 Create 伪装板）</h2>
 * <p>Create 的伪装板把材质存在 {@code CopycatBlockEntity} 的字段里（{@code private BlockState material}），
 * 由方块实体的 {@code saveAdditional} 写进 NBT —— <b>方块实体的 NBT 走到哪，材质就跟到哪</b>。
 * 本模组的伪装是「裹在别人的方块外面」（RS 线缆 / 流体管道 / 传动杆，这些方块本身就有方块实体），
 * 因此等价载体就是<b>同一个方块实体上的一个数据附件</b>（{@link RsccCamouflageAttachment}）：
 * 它同样参与方块实体的 NBT 往返，也同样被 NeoForge 同步给观察者。</p>
 *
 * <h2>为什么必须换掉旧的「SavedData + 世界坐标键 + 独立渲染器」</h2>
 * <p>旧架构的载体是<b>世界坐标本身</b>（服务端 {@link net.minecraft.world.level.saveddata.SavedData}
 * 的键就是 {@code BlockPos}），渲染器再拿坐标回头查世界单独画一层壳。用户实测的两个症状都是它的直接后果：</p>
 * <ul>
 *     <li><b>残影</b>：「框架贴图仍然留在原地，并且也不知道怎么搞掉」—— 方块被搬走 / 被抹掉时
 *     （装置装配与物理化都走 {@code Level#setBlock(pos, AIR, flags)}，既不触发本模组的清理事件、
 *     也没有方块更新），坐标记录<b>还在</b>，独立渲染器于是继续在原地画壳；</li>
 *     <li><b>不跟随</b>：「并没有正确地跟随这个线缆」—— 搬走的那一份在新坐标上没有记录，
 *     而材质本来就没有存在「会跟着走的东西」里。</li>
 * </ul>
 * <p>新架构下这两件事<b>结构性不可能发生</b>：材质是方块实体的一部分，方块实体的生命周期
 * 就是外壳的生命周期 —— 方块没了，方块实体没了，材质没了，外壳也就没了（没有任何「按坐标反查」的余地）。</p>
 *
 * <h2>渲染：谁驱动</h2>
 * <p>外壳不再由本模组「另外画一层」，而是<b>由被裹方块自己的模型在烘焙时一起产出</b>
 * （见 {@code client/model/CamouflageShellModel}）：模型数据阶段从方块实体读附件，
 * 四元组阶段把「方块自己的几何 + 外壳几何」一起交出去。因此外壳是区块网格 / 装置网格的一部分：
 * 方块被搬走时网格里就没有它了（残影不可能），装置装配时它随装置一起被绘制（跟着走）。</p>
 *
 * <h2>语义（与旧版<b>逐条</b>保持一致）</h2>
 * <ul>
 *     <li><b>填充</b>：手持完整方块右键被裹格 → 记下材质与「被消耗的 1 个方块」（判定完全复用
 *     Create 的 {@code CopycatBlock#getAcceptedBlockState}，见 {@code RsccCamouflageInteraction}）；</li>
 *     <li><b>守恒</b>：填充消耗 1、取消 / 取下返还 1、破坏只掉「框架本体 + 当初扣下的那 1 份」，
 *     <b>不继承战利品表</b>；</li>
 *     <li><b>属性继承</b>：抗爆 / 挖速取填充方块（见 {@code CamouflagePropertyMixin}）；</li>
 *     <li><b>显示开关</b>：K 键的<b>全局</b>「只显示框架」仍按玩家、仍服务端权威
 *     （真值在玩家持久化数据里，与外壳材质完全解耦）。</li>
 * </ul>
 */
public final class RsccCamouflage {
    /**
     * 玩家<b>全局显示开关</b>在玩家持久化数据里的 NBT 键（「按 K 是<b>切换状态</b>，
     * 而不是单独把某一格显示出来」）。
     *
     * <p><b>为什么存在玩家自己身上（playerdata）而不是世界存档里</b>：这是<b>看的人</b>的偏好，
     * 不是世界的属性 —— 同一格里裹着的东西，甲玩家想按 K 看见填充方块、乙玩家想看框架，
     * 各看各的才对。放在玩家持久化数据里同时还满足「重登保留」，也天然按维度无关。</p>
     *
     * <p>注意它与外壳材质是<b>两条独立的链路</b>：外壳材质走方块实体附件（跟着方块走），
     * 显示开关走玩家数据（跟着人走）。</p>
     */
    public static final String HIDDEN_TAG = "rscc_camouflage_hidden";

    /**
     * <b>材质属性转发</b>期间的重入旗标（见 {@link #materialProperty}）。
     *
     * <p>与 {@link RsccShapeQueryGuard} 同一个理由：转发时会去问「填充方块自己的属性」，
     * 而填充方块也是方块 —— 它的属性查询会再次经过同一个 Mixin，于是构成
     * 「我 → 材质 → 我」的环。旗标把环切断在第 2 层（内层直接放行原版实现）。</p>
     */
    private static final ThreadLocal<Boolean> RESOLVING_MATERIAL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * 客户端只读的<b>全局显示开关</b>：{@code true} = 所有被伪装的格子都按「只显示框架」画
     * （见 {@code CamouflageShellModel}）。
     *
     * <p>与外壳材质走同一条「服务端权威 → 客户端只读」的规矩，但它是<b>按玩家</b>的状态，
     * 因此单独用一个轻量 S2C 单包（{@code SyncCamouflageRevealPacket}）下发。</p>
     */
    private static volatile boolean clientMaterialHidden;

    /**
     * <b>2026-10-01：上一轮那条「跨 tick 补烘 / 自造方块更新」的表已删除</b>（它治不了本，见下）。
     *
     * <h2>上一轮为什么是错的</h2>
     * <p>上一轮把玩家看到的「孤立单格不显示、随手在旁边放个方块就正常」解释成
     * <b>「附件包与重烘信号谁先到」的竞态</b>，于是补了两件事：所有状态改动额外走一次
     * <b>自造的方块更新</b>（{@code forceBlockUpdate} → 借 {@link #repushIfCamouflaged} 补发附件），
     * 以及一张「随后 5 个客户端 tick 各再烘一次」的坐标待办表。</p>
     * <p>这一轮逐层取证后可以确定那条推论是错的：</p>
     * <ul>
     *     <li>外壳的几何<b>不看</b>被裹方块自己的四元组、不看连接、不看邻居
     *     （见 {@code client/model/CamouflageShellModel#getQuads}）—— 所以「孤立态被裹模型返回空四元组」
     *     这个前提本身不成立：RS 的 {@code CableBakedModel} 在孤立态（{@code CableConnections} 全 false）
     *     走的是「只返回芯柱核心模型」那一支，<b>不是空集</b>（RS 源码 {@code CableBakedModel#getQuads}
     *     第 64-75 行 + 缓存装载器第 33-61 行）；RS 也<b>没有给线缆注册 BER</b>
     *     （{@code ClientModInitializer} 只注册了磁盘驱动器 / 储存监控器 / 便携网格 / 磁盘接口）。</li>
     *     <li>客户端那三条「让这一段重烘」的链路都是通的：{@code ClientLevel#sendBlockUpdated} →
     *     {@code LevelRenderer#blockChanged} → {@code setBlockDirty}（字节码实测：无条件标脏，
     *     不比较新旧状态）；附件由 NeoForge {@code AttachmentSync} 在 {@code setData} 与
     *     {@code ChunkWatchEvent.Sent} 两处下发，且 {@code sendToPlayer} 恒为 true。</li>
     * </ul>
     * <p>既然「重烘」不是瓶颈，「多烘几次」与「自造一次方块更新」就只是噪音：它们让每一条改动路径
     * 多走一遍原版方块更新广播 + 在客户端多盯 5 个 tick，却不改变任何一帧画出来的东西 ——
     * 而玩家看到的「放个方块就好了」只是<b>放置方块本身让这一段网格重烘了一次</b>（原版行为），
     * 并不是我们那条补丁起了作用。</p>
     *
     * <h2>收敛后的口径（最小必要）</h2>
     * <p>只保留<b>一个</b>「数据变了 → 请重烘」的信号：{@link #notifyClient}
     * （NeoForge 的附件同步只会把数据塞回方块实体，不会重烘网格），以及
     * {@link #repushIfCamouflaged}（方块实体被重新落地 / 子关卡拆解回来时补发附件 —— 那是另一条
     * 已验证的需求，与「孤立单格」无关）。自造的方块更新与跨 tick 补烘一律删除。</p>
     */

    private RsccCamouflage() {
    }

    /**
     * 一条伪装记录 —— <b>也就是附件里存的那份数据本身</b>。
     *
     * @param material      外壳材质（{@code null} = 还没选外观，此时外观是「框架本体」，绝不透视）
     * @param consumed      套上外壳时消耗掉的那 1 个方块物品（取下 / 拆掉时原样归还；创造模式为空）
     * @param frameConsumed 裹上时是否<b>真的扣掉了</b>一个框架本体。创造模式不扣，
     *                      因此取下 / 拆掉时也就不能归还 —— 记录这一个布尔位，
     *                      是为了让「物品收支严格守恒」在创造模式下同样成立（否则会凭空掉出框架）。
     */
    public record Camo(@Nullable BlockState material, ItemStack consumed, boolean frameConsumed) {
    }

    // ------------------------------------------------------------------
    // 查询：唯一入口 = 被裹方块自己的方块实体上的附件
    // ------------------------------------------------------------------

    /**
     * <b>取这一格的伪装记录</b>（唯一的取数入口：读方块实体上的附件）。
     *
     * <p><b>为什么参数是 {@link BlockGetter} 而不是 {@code Level}</b>：外壳的几何是在
     * <b>区块网格烘焙</b>与 <b>Create 装置烘焙</b>时产出的，那两条路径拿到的
     * {@code BlockAndTintGetter} 分别是 {@code RenderChunkRegion} 与
     * {@code VirtualRenderWorld}，都<b>不是</b> {@code Level}，但<b>都</b>实现了
     * {@code getBlockEntity(BlockPos)}（NeoForge 在 {@code RenderChunkRegion} 上补的、
     * Create 自己实现的）—— 因此这里直接按 {@code BlockGetter} 取数即可，
     * 两种烘焙路径与真实世界共用同一句话。</p>
     *
     * <p><b>未加载区块绝不加载</b>：真实世界路径先过一次 {@code isLoaded}（它只读
     * 「这个区块在不在内存里」，不会触发加载）。</p>
     *
     * <p>取数一律走 {@code getExistingDataOrNull}：<b>不</b>调用 {@code getData}
     * —— 后者在附件不存在时会顺手创建一份默认值塞进方块实体（给每一根线缆都塞一个空附件，
     * 既浪费内存又会被写进存档）。</p>
     */
    @Nullable
    public static Camo dataOf(@Nullable final BlockGetter level, @Nullable final BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        if (level instanceof final Level realLevel && !realLevel.isLoaded(pos)) {
            return null; // 未加载：绝不为了取一份数据去加载区块
        }
        final BlockEntity be = level.getBlockEntity(pos);
        return be == null ? null : be.getExistingDataOrNull(RsccCamouflageAttachment.CAMOUFLAGE.get());
    }

    /** 该坐标的伪装记录；没被裹住时返回 {@code null}（双端同一结论：附件由 NeoForge 同步）。 */
    @Nullable
    public static Camo get(@Nullable final BlockGetter level, @Nullable final BlockPos pos) {
        return dataOf(level, pos);
    }

    /** 该坐标的线缆 / 管道 / 传动杆是否被伪装外壳裹住（外壳存在即算，哪怕还没选外观）。 */
    public static boolean isCamouflaged(@Nullable final BlockGetter level, @Nullable final BlockPos pos) {
        return dataOf(level, pos) != null;
    }

    /**
     * <b>这一格当前的填充方块状态</b>（材质；{@code null} = 没裹住 / 还是空壳 / 本端不认识那个方块）。
     *
     * <p>它是「指向信息模组（Jade）该显示什么」「外壳着色取谁的色」两条口径的唯一数据来源，
     * 双端同一结论（客户端读的是 NeoForge 同步下来的同一份附件）。</p>
     */
    @Nullable
    public static BlockState material(@Nullable final BlockGetter level, @Nullable final BlockPos pos) {
        final Camo camo = dataOf(level, pos);
        return camo == null ? null : camo.material();
    }

    /** 这一格有没有「已经选好的外壳材质」（取下 / 换壳的分流要两端一致）。 */
    public static boolean hasMaterial(@Nullable final BlockGetter level, @Nullable final BlockPos pos) {
        return material(level, pos) != null;
    }

    /**
     * <b>形状覆盖</b>：这一格的碰撞 / 选中 / 视觉形状是否应该按<b>整格</b>算。
     *
     * <p>被裹住的格里放的仍然是线缆 / 管道 / 传动杆，它们的形状是「一根 4/16 的细芯 +
     * 若干连接臂」，而外壳是一整格：玩家看到的是一个整格方块，却撞不上去、准星也常常打不到。
     * 因此只要外壳在，就让这一格以<b>完整方块</b>的形状参与碰撞与选中。</p>
     *
     * <p><b>两步廉价短路</b>：①纯 {@code instanceof} 的方块族判定（绝大多数方块在这里就返回
     * false，连方块实体都不查）；②查一次附件。</p>
     *
     * <h2>形状路径<b>只能</b>做这两步（2026-09-26 崩溃修复留下的硬约束）</h2>
     * <p>本方法挂在 {@code BlockBehaviour.BlockStateBase#getShape / getCollisionShape / getVisualShape}
     * 上，方块状态烘焙期（{@code cache == null}）也会被调用。这里<strong>绝不能</strong>再触发任何
     * 形状查询（曾经调用过 {@code isCollisionShapeFullBlock} → 回到自身 → {@code Bootstrap} 栈溢出，
     * 详见 {@code CamouflageShapeMixin} 的类注释）。读一份方块实体附件不触发形状查询，因此安全。</p>
     */
    public static boolean overridesShape(@Nullable final BlockGetter level, @Nullable final BlockPos pos,
                                         @Nullable final BlockState state) {
        if (pos == null || !SeparationFrameGuard.isSheatheableFamily(state)) {
            return false;
        }
        return isCamouflaged(level, pos);
    }

    /**
     * <b>把「填充方块自己的某个属性」问一遍</b>：属性继承的唯一入口
     * （除战利品表外，抗爆 / 挖速等都继承填充方块）。
     *
     * <h2>为什么写成泛型 + Lambda</h2>
     * <p>要继承的属性不止一个，若每个属性各写一份「查记录 → 取状态 → 转发 → 复位旗标」，
     * 就等于把同一套判定 + 重入保护抄 N 遍，将来必然有两份漂移。这里只留一份判定，
     * 调用方给一句「怎么问材质」。</p>
     *
     * <h2>重入保护为什么是硬要求</h2>
     * <p>转发时会去问「填充方块自己」的属性，而填充方块也是方块 —— 若它的属性查询再次进入本方法，
     * 就构成无限递归。旗标在 {@code finally} 里复位（属性查询可能在任意线程 / 任意异常路径上发生），
     * 绝不能让一条线程永久停在「重入中」。</p>
     *
     * @param self  调用方自己那一格的方块状态（由 Mixin 直接给出，避免再查一次世界）
     * @param query 拿到填充方块状态后要问的属性；<b>不得</b>在它里面再调用本方法（会被旗标挡掉）
     * @return 属性的值；{@code null} = 不继承（调用方放行原版实现）
     */
    @Nullable
    public static <T> T materialProperty(@Nullable final BlockGetter level, @Nullable final BlockPos pos,
                                         @Nullable final BlockState self,
                                         final java.util.function.Function<BlockState, T> query) {
        if (pos == null || !SeparationFrameGuard.isSheatheableFamily(self) || RESOLVING_MATERIAL.get()) {
            return null;
        }
        final BlockState material = material(level, pos);
        if (material == null) {
            return null;
        }
        RESOLVING_MATERIAL.set(Boolean.TRUE);
        try {
            return query.apply(material);
        } finally {
            RESOLVING_MATERIAL.set(Boolean.FALSE);
        }
    }

    // ------------------------------------------------------------------
    // 「隐藏已填充方块」全局开关（按玩家，与外壳材质完全解耦）
    // ------------------------------------------------------------------

    /**
     * <b>服务端</b>：该玩家是否开着「隐藏已填充方块」的全局开关。
     * <p>服务端权威：真值只存在玩家的持久化数据里（{@link #HIDDEN_TAG}），
     * 客户端那份只是 S2C 下发的只读副本。</p>
     */
    public static boolean isMaterialHidden(@Nullable final Player player) {
        return player != null && player.getPersistentData().getBoolean(HIDDEN_TAG);
    }

    /**
     * <b>服务端</b>：翻转该玩家的全局开关并返回翻转后的值。
     *
     * <p><b>只写一个布尔位</b>：不碰世界、不碰任何方块实体、更不碰物品与流体 ——
     * 因此「按 K」这条路径的收支恒为 0，与「填充消耗 1 / 取消返还 1 / 破坏掉落 1 份」
     * 的守恒链完全无关。</p>
     */
    public static boolean toggleMaterialHidden(@Nullable final Player player) {
        if (player == null) {
            return false;
        }
        final net.minecraft.nbt.CompoundTag data = player.getPersistentData();
        final boolean hidden = !data.getBoolean(HIDDEN_TAG);
        data.putBoolean(HIDDEN_TAG, hidden);
        // 诊断（只读）：记录一次 K 键切换与「受影响格数」（= 当前被伪装的格子总数，会随开关一起重烘），
        // 并默认打一条可 grep 的日志行（用户不必敲任何指令即可在日志里看到这次切换的范围）。
        if (player.level() instanceof final net.minecraft.server.level.ServerLevel serverLevel) {
            final int affected = RsccDiag.camouflagedCount(serverLevel);
            RsccDiag.recordRevealToggle(affected, serverLevel.getGameTime());
            RsccAssemblyDebug.event("camouflage reveal toggle hidden=" + hidden + " affected=" + affected);
        }
        return hidden;
    }

    /** <b>客户端</b>：当前是否要把所有被伪装的格子画成「只显示框架」。只读镜像，默认 false。 */
    public static boolean isMaterialHidden() {
        return clientMaterialHidden;
    }

    /** 客户端：收包后写入只读镜像（只有 S2C 包处理会调用它）。 */
    public static void applyClientHidden(final boolean hidden) {
        clientMaterialHidden = hidden;
    }

    /** S2C：把这个玩家的全局开关状态发给他（登录 / 换维度 / 重生 / 每次切换后调用）。 */
    public static void syncHiddenTo(final ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new SyncCamouflageRevealPacket(isMaterialHidden(player)));
    }

    // ------------------------------------------------------------------
    // 改动（仅服务端调用；每一次改动都写同一份附件 + 通知客户端重烘）
    // ------------------------------------------------------------------

    /**
     * 给这一格裹上伪装（只写附件，物品消耗由调用方负责 —— 与分隔框架同一条守恒规则）。
     *
     * @param frameConsumed 调用方这一次是否<b>真的</b>从玩家手里扣掉了一个框架本体
     *                      （普通模式为 true、创造模式为 false）；取下 / 拆掉时按它决定要不要归还
     * @return {@code false} = 这一格本来就裹着（调用方据此给出「先取下」的提示，且<b>不</b>扣物品）
     */
    public static boolean add(final ServerLevel level, final BlockPos pos, final boolean frameConsumed) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be == null || be.getExistingDataOrNull(RsccCamouflageAttachment.CAMOUFLAGE.get()) != null) {
            return false;
        }
        write(level, be, new Camo(null, ItemStack.EMPTY, frameConsumed));
        return true;
    }

    /**
     * 一次给<b>一批</b>坐标裹上伪装（<b>只来自 FTB Ultimine 的连锁模式</b>的唯一批量入口）。
     *
     * <p><b>为什么不是循环调用 {@link #add}</b>：{@code add} 每次都要「写附件 + 通知客户端重烘」，
     * 一次连锁几十格就会发几十个通知。本方法把这一批当成<b>一次事务</b>：
     * 先统一写附件 → 一次按区块分组通知 → 返回真正新增的格数（调用方据此按格扣物品）。</p>
     *
     * <p><b>幂等</b>：已经裹住的坐标直接跳过（不重复消耗物品，也不重复通知）。</p>
     *
     * @return 真正<b>新增</b>的裹壳格数
     */
    public static int addAll(final ServerLevel level, final Collection<BlockPos> positions,
                             final boolean frameConsumed) {
        if (positions.isEmpty()) {
            return 0;
        }
        // LinkedHashSet 顺带完成去重（同一坐标只裹一次、只扣一个）
        final Set<BlockPos> targets = new LinkedHashSet<>();
        for (final BlockPos pos : positions) {
            if (pos != null && !isCamouflaged(level, pos)) {
                targets.add(pos);
            }
        }
        if (targets.isEmpty()) {
            return 0;
        }
        int added = 0;
        final List<BlockPos> written = new ArrayList<>(targets.size());
        for (final BlockPos pos : targets) {
            final BlockEntity be = level.getBlockEntity(pos);
            if (be == null) {
                continue;
            }
            be.setData(RsccCamouflageAttachment.CAMOUFLAGE.get(), new Camo(null, ItemStack.EMPTY, frameConsumed));
            written.add(pos);
            added++;
        }
        notifyClient(level, written);
        return added;
    }

    /**
     * 把这一格的外壳材质设成 {@code material}（并把消耗掉的那 1 个方块记下来用于归还）。
     *
     * <p><b>为什么换外壳不销毁旧外壳</b>：调用方负责先把旧记录里的 {@code consumed} 还给玩家，
     * 再调用本方法覆盖 —— 本方法只做「记录替换 + 通知」，一行物品逻辑都不写，
     * 免得两处各写一半导致某个分支漏还。{@code frameConsumed} 位取自原记录（换外壳不碰框架本体）。</p>
     */
    public static void setMaterial(final ServerLevel level, final BlockPos pos, final BlockState material,
                                   final ItemStack consumed) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return;
        }
        final Camo current = be.getExistingDataOrNull(RsccCamouflageAttachment.CAMOUFLAGE.get());
        if (current == null) {
            return; // 没裹住就不该有材质（调用方已先行判定，这里只做兜底防御）
        }
        write(level, be, new Camo(material, consumed, current.frameConsumed()));
    }

    /**
     * 把这一格外壳的<b>朝向</b>旋转 90°（手持「同一种」方块右键 = 转壳，潜行 = 反向）。
     *
     * <p><b>为什么只改「记录里的材质」就够</b>：外壳的朝向完全由材质方块状态自己携带
     * （原木的 {@code axis}、熔炉的 {@code facing} 等），而外壳与<b>被裹方块</b>是两个互不相干的东西：
     * 被裹方块的方块状态从头到尾没有被本模组写过（套壳只写一份附件），
     * 因此旋转外壳<b>不可能</b>改变被裹方块自身。</p>
     *
     * <p><b>为什么用返回值判「转不动」</b>：没有朝向属性的方块（石头、玻璃…）其 {@code rotate}
     * 返回的是<b>同一个实例</b>，此时调用方给一句「没有可旋转的朝向」的提示，而不是假装转过了。</p>
     *
     * @return 旋转后的新材质；{@code null} = 这一格没裹住 / 还是空壳 / 这个材质没有可旋转的朝向
     *     （三种情况调用方都<b>不得</b>改动任何物品）
     */
    @Nullable
    public static BlockState rotateMaterial(final ServerLevel level, final BlockPos pos, final boolean clockwise) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return null;
        }
        final Camo current = be.getExistingDataOrNull(RsccCamouflageAttachment.CAMOUFLAGE.get());
        if (current == null || current.material() == null) {
            return null;
        }
        final BlockState rotated = current.material()
            .rotate(clockwise ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90);
        if (rotated == current.material()) {
            return null; // 没有朝向属性：转了个寂寞，调用方给提示即可（不消耗、不写记录）
        }
        write(level, be, new Camo(rotated, current.consumed(), current.frameConsumed()));
        return rotated;
    }

    /**
     * 把这一格的伪装取下（附件一并删除）。
     *
     * <p><b>取下不改任何连接</b>：本功能从头到尾就没碰过连接判定，所以这里不需要任何刷新 ——
     * 外壳消失只是模型层的事（附件没了 → 下一次网格烘焙就没有外壳了）。</p>
     *
     * @return 被删掉的记录（调用方据此把「框架本体 + 已用外壳方块」还给玩家）；{@code null} = 本来就没裹
     */
    @Nullable
    public static Camo remove(final ServerLevel level, final BlockPos pos) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return null;
        }
        final Camo removed = be.removeData(RsccCamouflageAttachment.CAMOUFLAGE.get());
        if (removed == null) {
            return null;
        }
        notifyClient(level, List.of(pos));
        return removed;
    }

    /**
     * 一次取回<b>整段</b>伪装（连锁取回的唯一批量入口，与 {@link #addAll} 对称）。
     *
     * <p>守恒判据与单格路径逐字相同：只把「当初真的扣过」的东西还给玩家
     * （创造模式记录、空外壳一律不产出）。幂等：没裹住的坐标直接跳过。</p>
     *
     * @return 真正被取下的格数；{@code 0} = 这一批都没裹住（调用方据此把右键交回原版）
     */
    public static int removeAll(final ServerLevel level, final Player player, final Collection<BlockPos> positions) {
        if (positions.isEmpty()) {
            return 0;
        }
        // LinkedHashSet 顺带完成去重（同一坐标只取下一次、只还一份）
        final Set<BlockPos> targets = new LinkedHashSet<>();
        for (final BlockPos pos : positions) {
            if (pos != null && isCamouflaged(level, pos)) {
                targets.add(pos);
            }
        }
        if (targets.isEmpty()) {
            return 0;
        }
        final List<BlockPos> cleared = new ArrayList<>(targets.size());
        for (final BlockPos pos : targets) {
            final BlockEntity be = level.getBlockEntity(pos);
            if (be == null) {
                continue;
            }
            final Camo removed = be.removeData(RsccCamouflageAttachment.CAMOUFLAGE.get());
            if (removed == null) {
                continue;
            }
            cleared.add(pos);
            // 与 takeOff 同一条守恒规则：框架本体 / 外壳方块各自「当初真的扣过」才归还
            refund(player, removed);
        }
        notifyClient(level, cleared);
        return cleared.size();
    }

    /**
     * <b>只把外壳方块取下来</b>（框架本体与它的记录保留，这一格仍然是「已伪装」）。
     *
     * <p>「取回里面的方块」与「取下整个伪装」是两件事：本方法只清掉外壳材质（连同「被消耗的那个方块」），
     * <b>框架本体、当初扣没扣过框架全部原样保留</b>，于是那一格退回「框架本体」外观
     * （不透视），玩家立刻可以把另一个方块套上去。</p>
     *
     * @return 归还给玩家的外壳方块；{@code null} = 这一格没裹住，或者本来就是「还没选外观」的空壳
     *     （两种情况下调用方都<b>不得</b>产出任何物品，只给提示）
     */
    @Nullable
    public static ItemStack clearMaterial(final ServerLevel level, final BlockPos pos) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return null;
        }
        final Camo current = be.getExistingDataOrNull(RsccCamouflageAttachment.CAMOUFLAGE.get());
        if (current == null || current.material() == null) {
            return null;
        }
        write(level, be, new Camo(null, ItemStack.EMPTY, current.frameConsumed()));
        return current.consumed();
    }

    /**
     * 方块被<b>拆掉 / 被替换 / 被炸掉</b>后清掉这一格的伪装记录，并把
     * <b>框架本体 + 已使用的外壳方块原样掉回世界</b>（「破坏方块掉原型」）。
     *
     * <p><b>为什么挂在方块自己的 {@code onRemove} 上</b>：这与 Create 伪装板的
     * {@code CopycatBlock#onRemove} <b>同一个挂点</b>，而且它同时覆盖了旧版三个事件挂点
     * （玩家破坏 / 被替换 / 爆炸）—— 「被替换」那一条尤其重要：旧版在
     * {@code BlockEvent.EntityPlaceEvent} 里处理，而那一刻方块实体<b>已经被移除</b>，
     * 记录早就跟着没了，物品就<b>凭空销毁</b>了；{@code onRemove} 在方块实体被移除<b>之前</b>调用，
     * 因此这里读得到、也就还得回去。</p>
     *
     * <p><b>幂等</b>：读一次 → 删一次 → 掉一次；重复进入时已经读不到记录，什么都不做
     * （旧的三个事件挂点因此可以安全保留作为兜底，不会掉两份）。</p>
     */
    public static void onBlockChanged(final ServerLevel level, final BlockPos pos) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return;
        }
        final Camo removed = be.removeData(RsccCamouflageAttachment.CAMOUFLAGE.get());
        if (removed == null) {
            return;
        }
        if (removed.frameConsumed()) {
            Block.popResource(level, pos, new ItemStack(RS_Create_Compat.CAMOUFLAGE_FRAME_ITEM.get()));
        }
        if (!removed.consumed().isEmpty()) {
            Block.popResource(level, pos, removed.consumed().copy());
        }
        notifyClient(level, List.of(pos));
    }

    /**
     * 方块被移除时的入口（由 {@code mixin/block/CamouflageRemovalMixin} 挂在
     * {@code BlockBehaviour#onRemove} 上，双端只有服务端会调用）。
     *
     * <h2>{@code isMoving} 必须尊重（否则就是复制漏洞 / 弄坏「跟随」）</h2>
     * <p>Create 的装置装配是这样搬走方块的（{@code Contraption#removeBlocksFromWorld}）：
     * 先 {@code world.removeBlockEntity(pos)} 把方块实体<b>从世界里摘掉</b>，
     * 再用 {@code world.setBlock(pos, AIR, Block.UPDATE_MOVE_BY_PISTON | ...)} 抹掉方块 ——
     * 注意它用的是<b>活塞移动</b>标志。两点结论：</p>
     * <ol>
     *     <li>装配那一刻方块实体<b>已经不在世界里</b>，这里读不到附件 → 天然不会掉落
     *     （否则玩家会既拿到物品、又让外壳跟着装置走，等于复制）；</li>
     *     <li>即便如此，这里仍然显式判一次 {@code isMoving}：别的搬运方式（活塞推方块）会带着
     *     方块实体的 NBT 一起走，材质必须<b>留在方块实体里</b>跟着走，绝不能掉出来。</li>
     * </ol>
     */
    public static void onBlockRemoved(final ServerLevel level, final BlockPos pos, final boolean isMoving) {
        if (isMoving) {
            return; // 被搬运：材质随方块实体的 NBT 一起走，这里一个字节都不动
        }
        onBlockChanged(level, pos);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 写一份新记录并通知客户端重烘（所有单格改动的唯一出口，时序只有一处）。
     *
     * <p>两步就够，而且次序天然正确：{@code setData} 内部会走 NeoForge 的
     * {@code AttachmentSync}（把附件发给正在观察这一格的玩家），随后
     * {@link #notifyClient} 再告诉那些客户端「这一段网格脏了」。
     * 上一轮在这中间插的那次「自造方块更新」已删除（见类上面 2026-10-01 的说明）。</p>
     */
    private static void write(final ServerLevel level, final BlockEntity be, final Camo camo) {
        be.setData(RsccCamouflageAttachment.CAMOUFLAGE.get(), camo);
        notifyClient(level, List.of(be.getBlockPos()));
    }

    /** 按记录里的「当初真的扣过什么」归还框架本体与外壳方块（守恒的唯一出口）。 */
    private static void refund(final Player player, final Camo camo) {
        if (camo.frameConsumed()) {
            CamouflageFrameItem.give(player, new ItemStack(RS_Create_Compat.CAMOUFLAGE_FRAME_ITEM.get()));
        }
        if (!camo.consumed().isEmpty()) {
            CamouflageFrameItem.give(player, camo.consumed().copy());
        }
    }

    /**
     * <b>方块更新之后补一次伪装下发</b>：这一格确实裹着外壳时，把材质重新推给正在观察它的玩家，
     * 并请客户端重烘那一段网格。
     *
     * <h2>为什么需要它（2026-09-30：物理化 / 拆解后外壳不回来 / 不刷新）</h2>
     * <p>材质平时只靠一条链路到达客户端：NeoForge 的 {@code AttachmentSync#syncBlockEntityUpdate}，
     * 而它<b>只在两处</b>被触发 —— ① 我们主动 {@code setData}（换壳 / 取壳，已覆盖）；
     * ② {@code ChunkWatchEvent.Sent}（区块首次发给玩家）。「方块实体被重新落地」这条路径
     * <b>两处都不占</b>：Sable 的子关卡拆解（{@code SubLevelAssemblyHelper#moveBlocks}）是
     * {@code LevelChunk#setBlockState} + {@code BlockEntity#loadWithComponents}，全程不经过
     * 我们、也不经过原版 {@code ChunkMap}；此时客户端那一格的方块实体是<b>刚建出来的空对象</b>
     * （原坐标的方块实体在物理化那一刻已被移除），于是外壳回不来。</p>
     *
     * <h2>为什么挂在「方块更新」这一拍上</h2>
     * <p>拆解的最后一步是 {@code SubLevelAssemblyHelper#markAndNotifyBlock}，
     * 它 <b>明确调用了 {@code Level#sendBlockUpdated}</b>（实测字节码：{@code invokevirtual
     * net/minecraft/world/level/Level.sendBlockUpdated}）把这一格的方块状态广播给观察者。
     * 在那里补一刀有两个好处：① 时机正确 —— 方块更新包<b>先</b>发出去，客户端这时才认识这一格，
     * 我们的附件包<b>随后</b>到达，客户端一定找得到方块实体（反过来发就会被丢弃）；
     * ② 覆盖面广 —— 任何「重新落地一个带附件方块实体」的路径只要走原版方块更新，就自动被补上。</p>
     *
     * <p>没有材质时一行开销都不花：调用方（{@code mixin/level/ServerLevelBlockUpdateMixin}）
     * 先用「廉价方块族判定」挡掉非线缆 / 管道 / 传动杆的方块更新。</p>
     */
    public static void repushIfCamouflaged(final ServerLevel level, final BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return; // 绝不为了补一次同步去加载区块（与 dataOf 同一条纪律）
        }
        final BlockEntity be = level.getBlockEntity(pos);
        if (be == null || be.getExistingDataOrNull(RsccCamouflageAttachment.CAMOUFLAGE.get()) == null) {
            return;
        }
        // ① 重新下发材质：借用 NeoForge 自己的同步入口（它内部按「谁在观察这一格」选玩家）
        be.syncData(RsccCamouflageAttachment.CAMOUFLAGE.get());
        // ② 再要一次重烘：方块更新虽然会把这一段标脏，但那是异步烘焙，可能抢在附件包之前完成
        notifyClient(level, List.of(pos));
    }

    /**
     * 通知观察这些坐标的客户端<b>重新烘焙那一段网格</b>。
     *
     * <h2>为什么附件已经同步了，还要这一步</h2>
     * <p>材质随附件由 NeoForge 自动下发（{@code SyncAttachmentsPayload}），但客户端收到附件后
     * <b>不会</b>自动重烘区块网格（NeoForge 只把数据塞回方块实体）。外壳的几何是<b>烘焙产物</b>，
     * 必须让客户端把那一段标脏才会重新走模型 → 才看得到新外壳。</p>
     * <p>Create 的伪装板也是这么做的（{@code CopycatBlockEntity#redraw} 里那句
     * {@code level.sendBlockUpdated(pos, state, state, 16)}），本类只是把它搬到服务端一侧、
     * 用一个极轻的 S2C 包送到正确的玩家手里。</p>
     *
     * <p><b>这个包不带任何渲染数据</b>（只有坐标）：材质仍然只有一条来源 —— 方块实体附件。
     * 它只是「你手上那条附件变了，重烘一下」的信号，因此不属于「世界坐标反查」那条被删掉的链路。</p>
     */
    private static void notifyClient(final ServerLevel level, final List<BlockPos> positions) {
        if (positions.isEmpty()) {
            return;
        }
        // 同一个区块里的坐标合成一个包：链路套壳一次几十格时不会发几十个包
        final java.util.Map<ChunkPos, List<Long>> byChunk = new java.util.HashMap<>();
        for (final BlockPos pos : positions) {
            byChunk.computeIfAbsent(new ChunkPos(pos), key -> new ArrayList<>()).add(pos.asLong());
        }
        byChunk.forEach((chunk, packed) -> PacketDistributor.sendToPlayersTrackingChunk(
            level, chunk, new RefreshCamouflagePacket(List.copyOf(packed))));
    }
}
