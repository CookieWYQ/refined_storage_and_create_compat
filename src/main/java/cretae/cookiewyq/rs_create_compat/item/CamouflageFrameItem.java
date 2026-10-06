package cretae.cookiewyq.rs_create_compat.item;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.RsccCamouflage;
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
 * 伪装框架的物品形态：右键线缆 / 管道，把<b>那一格</b>裹上一层「看起来像普通方块」的外壳。
 *
 * <h2>它<b>不放置任何方块</b>（本轮修正的真因）</h2>
 * <p>上一版把本物品做成「伪装板方块」：{@code useOn} 判定通过后交给
 * {@code BlockItem#place}，结果方块落在了<b>被点线缆旁边那一格空气</b>里
 * （线缆不可被替换，原版的放置目标恒为「命中格 + 命中面」）。玩家看到的是
 * 「它可以<b>放在</b>这个管道或线缆<b>上面</b>」，而线缆本身没有任何变化 ——
 * 这就是用户三轮反馈的「套不上去」：真正生效的那条路径是「在旁边放了一个装饰方块」，
 * 而不是「把线缆裹起来」。用户同时追问「框架到底该怎么收回？好像没有设计吧」，
 * 也正因为那个方块被当成线缆的一部分，玩家不知道从哪把它取下来。</p>
 * <p>本次改为与<b>分隔框架完全一致</b>的做法（用户原话：「伪装框也是一样的」）：
 * 给那一格的方块实体挂一份伪装附件（{@link RsccCamouflage}），那一格<b>仍然是原来那根线缆 / 管道</b>，
 * 外壳由<b>被裹方块自己的模型</b>在烘焙时一起产出（{@code client/model/CamouflageShellModel}），
 * 因此外壳随方块走、方块不在就没有外壳。
 * 本类因此<b>不再</b>把交互交给 {@code super.useOn}，也不再有任何「放置方块」的分支。</p>
 *
 * <h2>行为（服务端权威）</h2>
 * <ul>
 *     <li><b>点在可套的方块上</b>（{@link SeparationFrameGuard#isSheathable(net.minecraft.world.level.BlockGetter, BlockPos, BlockState)}，
 *     与分隔框架同一句判定）→ <b>只裹玩家命中那一格</b>；普通模式消耗 1 个（创造模式不消耗）；</li>
 *     <li><b>点在不可套的方块上</b>（空气 / 流体 / 可替换方块 / 非整格外形）→ 什么都不做（不放置、不消费），
 *     只给一句 actionbar 提示；</li>
 *     <li><b>已经裹住的那一格 + 潜行右键（手持框架 / 空手 / 扳手都可以）</b> → 取下外壳并归还
 *     框架本体与已用掉的外壳方块（{@code RsccCamouflageInteraction}，不潜行时给一句提示，
 *     避免「随手一右键就把壳摘了」）；</li>
 *     <li><b>被裹的那一格被拆掉</b> → 框架本体 + 外壳方块原样掉回世界（{@link RsccCamouflage#onBlockChanged}）。</li>
 * </ul>
 *
 * <h2>「成片裹壳」只在 FTB Ultimine 的连锁模式下发生（本轮纠正）</h2>
 * <p>与分隔框架<b>完全同一条约定</b>（用户原话「伪装框也是一样的」）：上一版那条
 * 「不潜行右键 = 沿视线方向自动连裹一片」已被<b>彻底移除</b>（含它的 {@code min(32, 手持数量)} 上限）。
 * 不潜行 = 只裹命中那一格；真正的批量只有一条入口 —— 手持本物品、<b>按住 FTB Ultimine 的连锁键</b>
 * 右键一格可套的线缆 / 流体管道时，由 {@code support} 包里的连锁实现按 FTB 给出的候选成片裹上
 * （上限沿用 FTB 自己的 {@code max_blocks}）。没装 FTB Ultimine 时那条入口不存在，行为就是单格裹壳。</p>
 *
 * <p><b>不显示进度提示</b>：用户要求「那个『已连续透壳多少格』的提示文本不要显示」，
 * 因此单格与连锁共用同一句结果型反馈（语言文件里的计数文案已删除）。</p>
 *
 * <h2>物品收支（硬要求：绝不复制 / 销毁）</h2>
 * <p>套上消耗 1 个、取下归还 1 个；创造模式不消耗也不归还；被裹的线缆被拆掉时把
 * <b>框架本体</b>掉回世界（外壳方块由 {@code RsccCamouflage} 的记录一并归还），一进一出严格相抵。</p>
 */
public class CamouflageFrameItem extends RsccHelpBlockItem {
    /** 点在了非管道 / 非线缆的方块上：不放置，只提示。 */
    private static final Component HINT_PIPE_ONLY =
        Component.translatable("block.rs_create_compat.camouflage_frame.hint.pipe_only");
    /** 裹上了。 */
    private static final Component HINT_WRAPPED =
        Component.translatable("block.rs_create_compat.camouflage_frame.hint.wrapped");
    /** 已经裹着：提示潜行右键可取下。 */
    private static final Component HINT_REMOVE_HINT =
        Component.translatable("block.rs_create_compat.camouflage_frame.hint.remove_hint");
    /** 取下了。 */
    private static final Component HINT_UNWRAPPED =
        Component.translatable("block.rs_create_compat.camouflage_frame.hint.unwrapped");
    public CamouflageFrameItem(final Block block, final Component helpText) {
        super(block, helpText);
    }

    /**
     * 右键方块：裹上 / 取下 / 提示。<b>绝不放置方块</b>（不再调用 {@code super.useOn}）。
     *
     * <p><b>为什么必须在物品侧拦</b>：伪装框架的用途是「把线缆 / 管道裹起来」，用在别处毫无意义。
     * 判定复用与分隔框架<b>同一个入口</b>（{@link SeparationFrameGuard#isSheathable}），
     * 两个框架的「能用在哪」永远不会漂移。</p>
     */
    @Override
    public InteractionResult useOn(final UseOnContext context) {
        final Player player = context.getPlayer();
        final Level level = context.getLevel();
        if (player == null) {
            return InteractionResult.PASS;
        }
        final BlockPos pos = context.getClickedPos();
        // 传动杆：<b>完全无响应</b>（用户第 7 条：「不要显示『套不上』，直接无反应就行了」）。
        // 为什么仍然在这里显式返回 FAIL 而不是交给下面的 isSheathable 分支：那一分支会弹
        // HINT_PIPE_ONLY（「只能用在线缆 / 管道上」）—— 对传动杆来说那同样是一句废话提示；而且
        // FAIL 会终止交互，绝不会落到 super.useOn 去「在旁边放一个装饰方块」。
        // 判据复用 {@link SeparationFrameGuard#isShaft}，与分隔框架的渲染快速筛同一句话，不动它。
        if (SeparationFrameGuard.isShaft(level.getBlockState(pos))) {
            return InteractionResult.FAIL;
        }
        if (!SeparationFrameGuard.isSheathable(level, pos, level.getBlockState(pos))) {
            if (!level.isClientSide()) {
                player.displayClientMessage(HINT_PIPE_ONLY, true);
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
        if (RsccCamouflage.isCamouflaged(level, pos)) {
            return this.removeCamouflage(serverLevel, player, pos);
        }
        return this.applyCamouflage(serverLevel, player, context.getItemInHand(), pos);
    }

    /**
     * 裹上：<b>只作用于玩家命中那一格</b>（服务端权威）。
     *
     * <p><b>为什么不再有「不潜行 = 连裹一片」这一支</b>：用户明确要求默认右键<b>不要</b>批量 / 连锁
     * （「我的意思不是指直接让他批量套壳」「我并不是想让直接让空手套壳」）。成片裹壳改由
     * FTB Ultimine 的连锁键触发、走 {@code support} 包里的连锁实现，与本方法完全无关。</p>
     *
     * <p><b>顺序与守恒</b>：先写记录（{@link RsccCamouflage#add}）、再扣物品 ——
     * 与分隔框架完全同一条规则，因此「物品数 == 裹壳数」恒成立；创造模式不扣也不记录
     * （否则取下 / 拆掉时会凭空掉出框架）。</p>
     */
    private InteractionResult applyCamouflage(final ServerLevel level, final Player player, final ItemStack stack,
                                              final BlockPos pos) {
        // 创造模式不扣物品（consume 自己也会跳过），因此记录里那位「是否真的扣过框架」必须跟着为 false，
        // 否则取下 / 拆掉时会凭空掉出一个框架（复制漏洞）。
        final boolean frameConsumed = !player.hasInfiniteMaterials();
        if (!RsccCamouflage.add(level, pos, frameConsumed)) {
            player.displayClientMessage(HINT_REMOVE_HINT, true);
            return InteractionResult.SUCCESS;
        }
        stack.consume(1, player);
        player.displayClientMessage(HINT_WRAPPED, true);
        return InteractionResult.SUCCESS;
    }

    /** 取下：必须先潜行（避免随手一右键就摘壳）；取下后归还框架本体与外壳方块。 */
    private InteractionResult removeCamouflage(final ServerLevel level, final Player player, final BlockPos pos) {
        if (!player.isShiftKeyDown()) {
            player.displayClientMessage(HINT_REMOVE_HINT, true);
            return InteractionResult.SUCCESS;
        }
        final RsccCamouflage.Camo removed = RsccCamouflage.remove(level, pos);
        if (removed == null) {
            // 极罕见的竞态（记录刚被别的原因清掉）：不改动任何物品
            return InteractionResult.SUCCESS;
        }
        // 只归还「当初真的扣掉过」的东西：创造模式裹上的那位是 false，取下时也就不归还（不刷物品）。
        // 归还结果**不再**播报（用户明确要求删掉「创造模式 / 是否消耗 / 是否返还」这类解释性废话），
        // 只留一句极简结果；收支本身由记录里的 frameConsumed / consumed 保证守恒。
        if (removed.frameConsumed()) {
            give(player, new ItemStack(RS_Create_Compat.CAMOUFLAGE_FRAME_ITEM.get()));
        }
        if (!removed.consumed().isEmpty()) {
            give(player, removed.consumed().copy());
        }
        player.displayClientMessage(HINT_UNWRAPPED, true);
        return InteractionResult.SUCCESS;
    }

    /** 归还一件物品：背包放不下就掉在脚下（绝不凭空消失）。 */
    public static void give(final Player player, final ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }
}
