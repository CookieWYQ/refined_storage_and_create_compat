package cretae.cookiewyq.rs_create_compat.item;

import com.refinedmods.refinedstorage.common.support.BaseBlockItem;
import cretae.cookiewyq.rs_create_compat.support.RsccSheaths;
import cretae.cookiewyq.rs_create_compat.support.SeparationFrameGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 分隔框架的物品形态（普通版 / 无限版共用同一个类，只差 {@code infinite} 位）。
 *
 * <p>沿用工程既有做法：方块物品一律用 RS 的 {@link BaseBlockItem}
 * （第二个构造参数是方块 tooltip 里那行帮助文案）。</p>
 *
 * <h2>它现在<b>不放置任何方块</b>，而是「给目标方块套壳」</h2>
 * <p>用户要求「不要做一个单独的方块存在，应该是真正的把它套在里面」。因此
 * {@link #useOn} 全面接管物品的右键行为：</p>
 * <ul>
 *     <li><b>点在线缆 / 流体管道 / 任意完整方块上</b>
 *     （{@link SeparationFrameGuard#isSheathable(net.minecraft.world.level.BlockGetter, BlockPos, BlockState)}）
 *     → <b>只给玩家命中那一格</b>写「已套壳」记录（{@link RsccSheaths}），
 *     同时记下它<b>套上那一刻连到哪几侧</b>的连接快照。被套的格子仍然是原来那根管道 / 那块方块，
 *     它的能力、网络节点、流体内容一个字节都没变；变的只有两件事：
 *     <b>连接被冻结在这一刻的状态</b>（原本连着的照旧连，之后新放的邻居不再连上）、
 *     <b>外面多一层壳</b>；</li>
 *     <li><b>点在不可套的方块上</b>（空气 / 流体 / 可替换方块 / 非整格外形）→ 不放置、不生效，
 *     只给一句 actionbar 提示；</li>
 *     <li><b>已经套住的那一格 + 潜行右键</b> → 取下外壳（记录与快照一起删除，连接随之恢复自然判定）。
 *     不潜行时给一句提示，避免「随手一右键就把壳摘了」。</li>
 * </ul>
 *
 * <h2>「成片套壳」只在 FTB Ultimine 的连锁模式下发生（本轮纠正）</h2>
 * <p>用户原话：「我的意思不是指直接让他批量套壳，我的意思指的是<b>在连锁模式下就是 FTB 的连锁模式下</b>
 * 进行批量套上这个伪装框架」「我并不是想让直接让空手套壳」。因此上一版那条「不潜行右键 = 沿视线方向
 * 自动连套一片」已被<b>彻底移除</b>（含它的 {@code min(32, 手持数量)} 上限）：不潜行 = 只套命中那一格，
 * 与「按住连锁键」这条路径互不相干。真正的批量只有一条入口 ——
 * 手持本物品、<b>按住 FTB Ultimine 的连锁键</b>右键一格可套的线缆 / 流体管道时，
 * 由 {@code support} 包里的 FTB 集成把 FTB 给出的候选交给连锁实现成片套上
 * （上限沿用 FTB 自己的 {@code max_blocks}）。没装 FTB Ultimine 时那条入口不存在，行为就是单格套壳。</p>
 *
 * <p><b>不显示进度提示</b>：用户要求「那个『已连续透壳多少格』的提示文本不要显示」，
 * 因此单格与连锁共用同一句结果型反馈（语言文件里的计数文案已删除）。</p>
 *
 * <h2>物品收支（硬要求：绝不复制 / 销毁）</h2>
 * <ul>
 *     <li><b>普通版</b>：套上时消耗 1 个；取下时把这 1 个原样还回玩家（背包放不下就掉在脚下）。
 *     一进一出严格相抵；</li>
 *     <li><b>无限版</b>：套上不消耗、取下不归还、拆掉方块也不产出（数据侧同样没有掉落物表）；</li>
 *     <li><b>创造模式</b>：不消耗、也不归还（复用原版 {@code hasInfiniteMaterials()} 语义），
 *     避免创造下刷物品。</li>
 * </ul>
 *
 * <p><b>服务端权威</b>：所有状态改动只在服务端发生，客户端只返回「挥手」这个表现结果；
 * 客户端要用的套壳坐标由 {@code SyncSheathPositionsPacket}（S2C 快照）下发。</p>
 */
public class SeparationFrameItem extends RsccHelpBlockItem {
    /** 点在了非管道 / 非线缆的方块上：不放置，只提示。 */
    private static final Component HINT_NEED_PIPE =
        Component.translatable("message.rs_create_compat.frame_need_pipe");
    /** 套上了。 */
    private static final Component HINT_SHEATHED =
        Component.translatable("message.rs_create_compat.frame_sheathed");
    /** 已经套着：提示潜行右键可取下。 */
    private static final Component HINT_REMOVE_HINT =
        Component.translatable("message.rs_create_compat.frame_remove_hint");
    /** 取下了。 */
    private static final Component HINT_UNSHEATHED =
        Component.translatable("message.rs_create_compat.frame_unsheathed");

    /** 无限版标记。 */
    private final boolean infinite;

    public SeparationFrameItem(final Block block, final Component helpText, final boolean infinite) {
        super(block, helpText);
        this.infinite = infinite;
    }

    public boolean isInfiniteVariant() {
        return infinite;
    }

    /**
     * 无限版的<b>物品</b>带附魔光泽（用户要求「物品本身就是手拿的那个物品 + 有附魔光泽」）。
     *
     * <p>套壳格表面那一层光泽由 {@code client/SheathRenderer} 用 {@code RenderType#glint()}
     * 叠画（isFoil 管不到世界里的那一层壳）；这里补的是物品栏 / 手持图标 / 掉落物那一侧的流光，
     * 两者合起来才是「物品和方块都有光泽」。普通版恒为 false，与原版行为一致。</p>
     */
    @Override
    public boolean isFoil(final ItemStack stack) {
        return infinite;
    }

    /**
     * 右键方块：套壳 / 取下壳 / 提示。绝不放置方块（覆盖掉 {@code BlockItem#useOn} 的放置行为）。
     */
    @Override
    public InteractionResult useOn(final UseOnContext context) {
        final Player player = context.getPlayer();
        final Level level = context.getLevel();
        final BlockPos pos = context.getClickedPos();
        if (player == null) {
            return InteractionResult.PASS;
        }
        final BlockState targetState = level.getBlockState(pos);
        if (!SeparationFrameGuard.isSheathable(level, pos, targetState)) {
            // 强制约束：只能作用在「可套壳方块」上（管道 / 线缆族，或任意完整方块 —— 见本轮放宽）。
            // 这里刻意「什么都不做」——既不放置方块，也不消费物品，只用一句话告诉玩家为什么没反应。
            if (!level.isClientSide()) {
                player.displayClientMessage(HINT_NEED_PIPE, true);
            }
            return InteractionResult.FAIL;
        }
        if (level.isClientSide()) {
            // 客户端只负责挥手（真正的状态改动全在服务端，见类注释）
            return InteractionResult.SUCCESS;
        }
        if (!player.mayBuild()) {
            return InteractionResult.FAIL;
        }
        final ServerLevel serverLevel = (ServerLevel) level;
        if (RsccSheaths.isSheathed(level, pos)) {
            return this.removeSheath(serverLevel, player, context.getItemInHand(), pos);
        }
        return this.applySheath(serverLevel, player, context.getItemInHand(), pos);
    }

    /**
     * 套上：<b>只作用于玩家命中那一格</b>（服务端权威）。
     *
     * <p><b>为什么不再有「不潜行 = 连套一片」这一支</b>：用户明确要求默认右键<b>不要</b>批量 / 连锁
     * （「我的意思不是指直接让他批量套壳」）。成片套壳改由 FTB Ultimine 的连锁键触发、
     * 走 {@code support} 包里的连锁实现，与本方法完全无关；因此潜行与否在这里不再有意义
     * （潜行仍然只保留「取下」那一档，见 {@link #removeSheath}）。</p>
     *
     * <p><b>顺序</b>：先写记录、再扣物品（记录写不进去就绝不扣，保证「物品数 == 套壳数」）。</p>
     */
    private InteractionResult applySheath(final ServerLevel level, final Player player, final ItemStack stack,
                                          final BlockPos pos) {
        // 「这一次是否真的会扣掉 1 个」必须在扣之前算好并记进记录：取下 / 被拆时按它决定还不还，
        // 于是「创造模式下套上（没扣）→ 生存玩家取下」也不会凭空产出一个框架（与伪装框架同一条守恒规则）。
        final boolean paid = !infinite && !player.hasInfiniteMaterials();
        if (!RsccSheaths.add(level, pos, infinite, paid)) {
            player.displayClientMessage(HINT_REMOVE_HINT, true);
            return InteractionResult.SUCCESS;
        }
        // 普通版消耗 1 个；无限版不消耗（用户要求「使用时不会被消耗」）。
        // consume() 在创造模式下由原版自行判定为不消耗（hasInfiniteMaterials），无需在此重复判断。
        if (!infinite) {
            stack.consume(1, player);
        }
        player.displayClientMessage(HINT_SHEATHED, true);
        return InteractionResult.SUCCESS;
    }

    /**
     * 取下：必须先潜行（避免随手一右键就摘壳）；取下后按记录里的守恒位归还框架本体。
     *
     * <p>归还判定与「背包满则掉在脚下」集中在 {@link RsccSheaths#refund}：全局挂点
     * （{@code support/RsccSheathInteraction}，空手 / 手持任一种框架本体都能取下）走的是同一份实现，
     * 两处不可能给出不一样的收支。扳手不在这两条取下路径里 —— 它是 RS / Create 自己的拆除工具，
     * 潜行 + 扳手整体让给它们（拆掉方块并掉落原方块），套壳记录与框架本体由拆除后的收尾挂点处理。</p>
     */
    private InteractionResult removeSheath(final ServerLevel level, final Player player, final ItemStack stack,
                                           final BlockPos pos) {
        if (!player.isShiftKeyDown()) {
            player.displayClientMessage(HINT_REMOVE_HINT, true);
            return InteractionResult.SUCCESS;
        }
        // 留下「取回框架」的第二条路径：这里消费掉这次右键，避免同一次右键又被原版拿去放置方块。
        final RsccSheaths.SheathRemoval removed = RsccSheaths.remove(level, pos);
        if (removed == null) {
            // 极罕见的竞态（记录刚被别的原因清掉）：不改动任何物品
            return InteractionResult.SUCCESS;
        }
        // 按记录里的守恒位归还（{@link RsccSheaths#refund}）。
        // 归还结果**不再**播报：用户明确要求「取下来的时候也不需要说什么创造模式什么的、会消耗什么的、
        // 不返还什么的」—— 只留一句极简的操作结果，收支本身由 RsccSheaths 的 paid 位保证守恒。
        RsccSheaths.refund(player, removed);
        player.displayClientMessage(HINT_UNSHEATHED, true);
        return InteractionResult.SUCCESS;
    }
}
