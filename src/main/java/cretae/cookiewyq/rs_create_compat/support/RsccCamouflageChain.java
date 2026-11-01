package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.CamouflageFrameItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「<b>连锁应用材质</b>」的唯一实现：手持<b>要被伪装的方块</b>（例如黑曜石）+ 按住 FTB Ultimine 的连锁键
 * + 右键一格<b>已经裹着伪装</b>的线缆 / 管道 ⇒ 连锁范围内所有伪装格都换成这块材质。
 *
 * <h2>它补的是哪一段（用户原话）</h2>
 * <p>「就比如说我想把一大片（的）连（锁）伪装框架全都连锁，然后呢把它们全都设定成什么，
 * 比如说黑曜石之类的。然后这个功能你并没有实现。」</p>
 * <p>在此之前，连锁只有两条语义：{@link RsccSheathChain#chainSheath} 管「套上 / 取下框架」、
 * {@link RsccBusClipboard#chainPaste} 管「批量粘贴总线配置」。<b>「把一整段的外壳材质改成同一种」
 * 这一步只存在于右键单格</b>（{@code support/RsccCamouflageInteraction#applyMaterial}）——
 * 连锁那一支遇到手里拿的是方块时，会一路「不是框架、不是剪贴板」直接返回 0，
 * 于是玩家按住连锁键右键只改到了命中那一格。本类就是补上的那一步。</p>
 *
 * <h2>触发与既有连锁的分工（不冲突）</h2>
 * <ol>
 *     <li>{@link RsccUltimineIntegration} 先按手里的物品分派：剪贴板 → 总线批量粘贴；</li>
 *     <li>再交给 {@link RsccSheathChain#chainSheath}（框架的套上 / 取下，含「无限版」与「潜行取回」）；</li>
 *     <li><b>只有它返回 0</b>（= 这次连锁与框架无关 / 框架那条明确不接手）<b>且</b>
 *     手里是「能被 Create 当成外壳的方块」<b>且</b>命中格<b>已经是伪装格</b>时，才轮到本类。</li>
 * </ol>
 * <p>因此：拿框架连锁 = 套 / 取框架（语义一字未改）；拿方块连锁 = 改材质；
 * 拿别的东西连锁 = 本模组不接手（返回 0，交回 FTB / 原版）。</p>
 *
 * <h2>硬约束（逐条落在实现里）</h2>
 * <ul>
 *     <li><b>服务端权威</b>：只接受 {@link ServerPlayer}（FTB 也只在服务端分发右键连锁），
 *     客户端一行状态都不写；</li>
 *     <li><b>上限</b>：沿用 FTB 给这名玩家生效的 {@code ShapeContext#maxBlocks()}（{@code limit}），
 *     再叠加「手里真的有那么多块」（普通模式）—— 与 {@link RsccSheathChain} 的
 *     {@code affordable} 同一套口径，本模组不另立数值；</li>
 *     <li><b>去重</b>：{@link RsccSheathChain#planTargets} 用 {@code LinkedHashSet} 保序去重，
 *     本类再按坐标过滤一次，同一格绝不会被写两次、扣两次；</li>
 *     <li><b>逐块 try/catch</b>：写入在 {@link RsccCamouflage#setMaterialAll} 里逐格兜住异常，
 *     一格失败不影响整批；失败格数<b>必须</b>报给玩家（不许静默）；</li>
 *     <li><b>权限 / 距离</b>：{@code player.mayBuild()} +
 *     {@link RsccBusClipboard#ULTIMINE_MAX_DISTANCE}（与总线连锁粘贴同一个 32 格上限）；</li>
 *     <li><b>同步</b>：写入走 {@code be.setData(...)}（附件由 NeoForge 下发），
 *     再由 {@link RsccCamouflage#setMaterialAll} 发一次「请重烘」——
 *     客户端读到的就是服务端写的同一份数据；</li>
 *     <li><b>守恒</b>：先写记录、再扣新材质；旧外壳方块原样归还。
 *     创造模式不扣也不还（记录里 {@code consumed} 为空），因此一进一出严格相抵。</li>
 * </ul>
 */
public final class RsccCamouflageChain {
    /** 连锁应用材质成功（与单格「换 / 装外壳」共用同一句结果型反馈，不带格数）。 */
    private static final String MSG_APPLIED = "block.rs_create_compat.camouflage_frame.hint.shelled";
    /** 连锁范围里没有可改的伪装格（或全被上限 / 距离 / 数量挡掉）。 */
    private static final String MSG_NO_TARGET = "message.rs_create_compat.camouflage_chain.none";
    /** 有一部分格没写成功：{@code %s} = 失败格数。 */
    private static final String MSG_PARTIAL = "message.rs_create_compat.camouflage_chain.partial";
    /** 手里那块方块当不了外壳（与单格路径共用同一句：只接受完整方块）。 */
    private static final String MSG_FULL_BLOCK_ONLY = "block.rs_create_compat.camouflage_frame.hint.full_only";
    /** 没有建造权限（与总线连锁粘贴同一句）。 */
    private static final String MSG_CANNOT_BUILD = "message.rs_create_compat.bus_clipboard.cannot_build";

    private RsccCamouflageChain() {
    }

    /**
     * 手里这一件物品<b>有没有资格</b>当「连锁应用的材质」。
     *
     * <p>只做最便宜的一半（{@link BlockItem}）：真正「这块方块能不能当外壳」的判定必须复用
     * Create 的 {@code CopycatBlock#getAcceptedBlockState}（见
     * {@code RsccCamouflageInteraction#readMaterial}），绝不在本类另立一份口径。</p>
     *
     * <p>框架本体（{@code CamouflageFrameItem} / {@code SeparationFrameItem}）也继承自
     * {@code BlockItem}，但它们在上游分派里已经被框架那一支接走了；即便漏到本类，
     * 也会在「命中格不是伪装格」或 {@code getAcceptedBlockState} 的
     * {@code block instanceof CopycatBlock → null} 那一句被挡掉。</p>
     */
    public static boolean isMaterialItem(final ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof BlockItem;
    }

    /**
     * 连锁应用材质（服务端权威）。
     *
     * @param player  玩家（服务端）
     * @param hand    拿材质的那只手（扣物品作用在这一格）
     * @param anchor  FTB 本次连锁的目标格（{@code ShapeContext#origPos()}）
     * @param candidates FTB 给出的候选坐标（已按它的形状与 {@code max_blocks} 限制过）
     * @param limit   本次允许处理的最大格数（{@code ShapeContext#maxBlocks()}）
     * @return 实际写入的格数；{@code 0} = 本模组不接手这次连锁
     *     （调用方必须把右键交回 FTB / 原版，行为与「没装本模组的连锁材质」时完全一致）
     */
    public static int chainApplyMaterial(final ServerPlayer player, final InteractionHand hand,
                                         final BlockPos anchor,
                                         final Collection<BlockPos> candidates, final int limit) {
        if (player == null || anchor == null || limit <= 0) {
            return 0;
        }
        final ServerLevel level = player.serverLevel();
        if (!level.isLoaded(anchor)) {
            return 0; // 未加载：绝不为了连锁去加载区块
        }
        // 只处理「已经是伪装格」的那些：命中格都没裹住时，这次右键与「改材质」无关，
        // 原样交回 FTB / 原版（玩家可能是想放方块）。
        if (!RsccCamouflage.isCamouflaged(level, anchor)) {
            return 0;
        }
        final ItemStack stack = player.getItemInHand(hand);
        if (!isMaterialItem(stack)) {
            return 0; // 空手 / 扳手 / 工具：不是「拿方块改材质」这个意图
        }
        if (!player.mayBuild()) {
            // 冒险模式 / 被保护限制：这是「改不了」，必须说出来（与总线连锁粘贴同一条规矩）
            player.displayClientMessage(Component.translatable(MSG_CANNOT_BUILD), true);
            return 1; // 吃掉这次连锁右键：否则原版会把方块放到命中面上，看起来像「连锁生效了一半」
        }
        // 材质方块状态的朝向按玩家视线取最近的一面（与 Create 伪装板「放置时用主朝向」同一条口径）
        final Direction face = Direction.orderedByNearest(player)[0];
        final BlockState material = RS_Create_Compat.CAMOUFLAGE_FRAME_BLOCK.get()
            .getAcceptedBlockState(level, anchor, stack, face);
        if (material == null) {
            // 手里那块方块当不了外壳（台阶 / 带方块实体的机器 / 非整格外形 …）：
            // 与单格路径共用同一句提示，绝不静默、也绝不动任何一格
            player.displayClientMessage(Component.translatable(MSG_FULL_BLOCK_ONLY), true);
            return 1;
        }
        final boolean free = player.hasInfiniteMaterials();
        final List<BlockPos> planned = RsccSheathChain.planTargets(level, anchor, candidates, limit);
        // 再筛一道：只留「真的裹着 + 离玩家够近 + 这次付得起」的格，并保序去重。
        final Set<BlockPos> targets = new LinkedHashSet<>();
        for (final BlockPos pos : planned) {
            if (pos == null || !level.isLoaded(pos)) {
                continue; // 未加载：不为一格判定去加载区块
            }
            if (!RsccCamouflage.isCamouflaged(level, pos)) {
                continue; // 同族但还没套框架的格：连锁改材质不该顺手给它裹上
            }
            if (!withinReach(player, pos)) {
                continue; // 玩家已经走远（FTB 的候选可能伸到 32 格外）
            }
            if (targets.size() >= limit) {
                break; // FTB 对这名玩家生效的上限
            }
            if (!free && targets.size() >= stack.getCount()) {
                break; // 普通模式：手里只有这么多块，只改付得起的那些（先选中的、先改）
            }
            targets.add(pos);
        }
        if (targets.isEmpty()) {
            // 有伪装格 / 但都被上限、距离或数量挡掉：说出来（否则玩家看到的是「连锁没反应」）
            player.displayClientMessage(Component.translatable(MSG_NO_TARGET), true);
            return 1;
        }
        final ItemStack consumedPerCell = free ? ItemStack.EMPTY : stack.copyWithCount(1);
        final RsccCamouflage.BulkWrite result =
            RsccCamouflage.setMaterialAll(level, new ArrayList<>(targets), material, consumedPerCell);
        if (result.changed() <= 0) {
            // 一格都没写进去（方块实体刚好全没了 / 附件被并发清掉）：这不是「成功」，必须报出来。
            // 计数取「失败格数」，异常情况下（一格都没走到失败分支却也没写）退回本次目标数 —— 不重复计数。
            final int failed = result.failed() > 0 ? result.failed() : targets.size();
            player.displayClientMessage(Component.translatable(MSG_PARTIAL, failed), true);
            return 1;
        }
        // 先写记录、再扣物品（与单格路径同一条守恒规则）：只扣真正写入的格数
        if (!free) {
            stack.consume(result.changed(), player);
        }
        // 旧外壳方块原样归还（创造模式套上的记录里 consumed 为空，因此不产出 —— 不刷物品）
        for (final ItemStack refund : result.refunds()) {
            CamouflageFrameItem.give(player, refund.copy());
        }
        if (result.failed() > 0) {
            player.displayClientMessage(Component.translatable(MSG_PARTIAL, result.failed()), true);
        } else {
            player.displayClientMessage(Component.translatable(MSG_APPLIED), true);
        }
        return result.changed();
    }

    /**
     * 与 {@link RsccBusClipboard} 的连锁粘贴<b>同一个距离上限</b>（那个常量就是「连锁能伸多远」的唯一定义）。
     * <p>写成方法而不是在循环里重复算式：距离口径只能有一处，将来调上限时两处不会漂移。</p>
     */
    private static boolean withinReach(final ServerPlayer player, final BlockPos pos) {
        return player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)
            <= RsccBusClipboard.ULTIMINE_MAX_DISTANCE * RsccBusClipboard.ULTIMINE_MAX_DISTANCE;
    }
}
