package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.support.RsccCableCuts.Mode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 「机械动力扳手分离 RS 线缆」的交互挂点。
 *
 * <p><b>挂点</b>：NeoForge 的 {@link PlayerInteractEvent.RightClickBlock}（右键点方块，双端触发，
 * 在 {@code Item#onItemUseFirst / Block#use / Item#useOn} 之前）。Create 自己的扳手逻辑走
 * {@code WrenchItem#useOn} → {@code IWrenchable}（Create 方块）与
 * {@code WrenchEventHandler}（其它模组的扳手，优先级 HIGH，仅对 {@code IWrenchable}）；
 * RS 的线缆方块既不是 {@code IWrenchable}，也不带 Create 的 {@code wrench_pickup} 标签，
 * 扳手点上去本来就什么也不做，因此这里接管<b>不会改变 Create 扳手对其它方块的任何行为</b>。</p>
 *
 * <p><b>接管条件（全部满足才 cancel 事件）</b>：</p>
 * <ol>
 *     <li>当前档位不是 {@code off}（{@code off} 时本模组完全不介入）；</li>
 *     <li>手持物品带 {@code c:tools/wrench} 标签（Create 扳手已在标签里，其它模组的扳手同理可用）；</li>
 *     <li>{@code player.mayBuild()}（与 Create 一致，冒险模式不生效）；</li>
 *     <li><b>没有潜行</b>：潜行时一律不接管，把 Create / 原版的「拆装 / 拾取」类操作完整让出去；</li>
 *     <li>目标方块是 RS 线缆类方块（{@link RsccWireBlocks#isWire}：线缆 / 输出总线 / 输入总线）。</li>
 * </ol>
 * <p>不满足任一条即 {@code return}，<b>绝不 cancel</b>。</p>
 *
 * <p><b>「按面」判定（用户本轮确认的语义）</b>：断开点就是<b>两根线缆之间那一段接缝</b>
 * （两个方块中间的那个平面），因此这里取准星命中的面
 * （{@link PlayerInteractEvent.RightClickBlock#getFace()}）作为接缝方向交给
 * {@link RsccCableCuts#toggleSeam}：切这一道缝<b>只影响这一侧</b>，
 * 两个方块各自与其它面的连接照旧；两侧都是线缆族时那道缝<b>永远可断</b>
 * （不再要求「围成一圈 / 当前一定有连接臂」）。</p>
 *
 * <p><b>2026-09-25 修正：选缝要结合玩家视角</b>（用户实测「朝正东看却切了北/南那道缝」）。
 * 线缆外形是一根细芯，射线往往打在芯的<b>上/侧面</b>，命中面因此不等于玩家想切的那道缝。
 * 于是 SEAM 档位改成由 {@link RsccSeamPick} 选缝：<b>先看玩家视线与缝法线的夹角
 * （正对的那一道优先），再看准星落点到缝平面的距离，最后按固定顺序兜底</b>；
 * 客户端预览用<b>同一个方法</b>选缝，所以预览的那道缝就是真正被切的那道。
 * FACE 档位不受影响（它的语义本来就是「命中的这个面」）。</p>
 *
 * <p><b>服务端权威</b>：状态只在服务端改（{@link RsccCableCuts}）；客户端只负责取消事件 + 挥手，
 * 界面上的连接臂由服务端下发的方块实体数据与 S2C 快照决定。</p>
 *
 * <h2>2026-09-26 修正：不再做任何「过程性播报」（用户明确要求删掉）</h2>
 * <p>用户原话：「我拨下线缆之后，它提示断了」「你先这些细，也就是我点击继续、检测器重新计时，
 * 这些话语不要有」。断开 / 恢复是<b>玩家自己的动作</b>，动作栏再复述一遍（「已断开：此处不再连接」/
 * 「已恢复：此处重新连接」/「这里没有可断开的连接」/「该面已停止自动连接」/「该面已恢复自动连接」）
 * 纯属噪声：连接臂的变化在世界上本来就看得到，提示反而把真正的状态信息（例如挂起横幅）淹掉。
 * 因此<b>这些文案与它们的语言键一并删除</b>，切换动作本身（{@link RsccCableCuts#toggleSeam} /
 * {@link RsccCableCuts#toggleFace}）与成就触发（{@code RsccAdvancements#onSeamToggled}，
 * 由 {@link RsccCableCuts} 内部发出）<b>一字未改</b>。</p>
 *
 * <h2>2026-09-25 修正：优先级必须是 {@link EventPriority#HIGH}（用户报的「断开后不能再连接」）</h2>
 * <p>用户原话：「现在可以断开，为什么不能再连接？就是断开的地方怎么继续连接？」。根因不在
 * {@link RsccCableCuts#canToggleSeam} 的「已断开 → 再扳恢复」分支（那一条一直都在、也一直可逆），
 * 而在这条右键<b>根本没轮到本类</b>：RS 自己也挂了 {@code PlayerInteractEvent.RightClickBlock}
 * （{@code ModInitializer#registerWrenchingEvent} → {@code AbstractBaseBlock#tryUseWrench}），
 * 只要手里是扳手、目标是 RS 方块，它就<b>无条件</b>
 * {@code setCanceled(true)}（哪怕这次什么都没做），返回 SUCCESS。两个监听器原本都在默认优先级，
 * 谁先谁后只取决于<b>模组注册顺序</b>——于是「能不能断 / 能不能恢复」变成了一场竞态：
 * 轮到 RS 先跑时，本类连一次都进不来，玩家看到的就是「这件事时灵时不灵、断开的地方再也连不上」。</p>
 * <p>修法：把本类提到 {@link EventPriority#HIGH}（与 {@code RsccCamouflageInteraction} /
 * {@code RsccSheathInteraction} 同级），于是本类<b>永远</b>先于 RS 的默认优先级处理——
 * 该接管时一定接管，且「断开」与「恢复」走的是同一条被接管的路径，不再有任何模式差异。
 * 不接管时（档位 OFF / 手里不是扳手 / 潜行 / 不是 RS 线缆）依旧一行都不改地放行给 RS / 原版。</p>
 *
 * <p><b>顺带负责</b>：把当前维度的断开快照在「登录 / 换维度 / 重生」时下发给该玩家
 * （{@link RsccCableCuts#syncTo}），保证预置的断开在客户端一进世界就被认到。</p>
 */
public final class RsccWrenchCableInteraction {

    private RsccWrenchCableInteraction() {
    }

    /** 注册到 NeoForge 全局事件总线（由主类构造函数调用一次）。 */
    public static void register() {
        NeoForge.EVENT_BUS.register(new RsccWrenchCableInteraction());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled()) {
            return; // 更外层（伪装外壳 / 分隔框架）已经处理了这次右键：一次右键只结算一层
        }
        final Level level = event.getLevel();
        final BlockPos pos = event.getPos();
        final Direction face = event.getFace();
        if (face == null) {
            return;
        }
        final Mode mode = RsccCableCuts.modeFor(level);
        if (mode == Mode.OFF) {
            return;
        }
        final ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !stack.is(Tags.Items.TOOLS_WRENCH)) {
            return;
        }
        final Player player = event.getEntity();
        if (!player.mayBuild() || player.isShiftKeyDown()) {
            return;
        }
        if (!RsccWireBlocks.isWire(level.getBlockState(pos))) {
            return; // 不是 RS 线缆：不接管，Create 扳手对其它方块的行为原样保留
        }
        // 到哪一道缝：SEAM 档位按「玩家正对的那一道」选（见 RsccSeamPick 的选缝规则），
        // FACE 档位仍然就是准星命中的那个面（那个面的语义本来就是「贴在这个面上」）。
        final Direction target = mode == Mode.SEAM
            ? RsccSeamPick.pick(level, pos, player.getLookAngle(), event.getHitVec().getLocation())
            : face;
        // 到这里才接管：双端一致地取消事件 + 返回 SUCCESS（客户端据此挥手，不会去打别的交互）
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (level instanceof final ServerLevel serverLevel) {
            apply(serverLevel, pos, face, target);
        }
    }

    @SubscribeEvent
    public void onLoggedIn(final PlayerEvent.PlayerLoggedInEvent event) {
        sync(event.getEntity());
    }

    @SubscribeEvent
    public void onChangedDimension(final PlayerEvent.PlayerChangedDimensionEvent event) {
        sync(event.getEntity());
    }

    @SubscribeEvent
    public void onRespawn(final PlayerEvent.PlayerRespawnEvent event) {
        sync(event.getEntity());
    }

    // ------------------------------------------------------------------
    // 断点记录的失效：方块被拆掉 / 被替换 / 重新放置
    // ------------------------------------------------------------------

    /**
     * 玩家破坏方块：清掉与这个位置相关的断开记录<b>与套壳记录</b>。
     * <p>用「玩家主动破坏」事件而不用「邻块更新」：本模组自己的刷新动作不会触发它，
     * 因此不会把刚写下的断开记录误清掉。</p>
     * <p><b>为什么是 {@link EventPriority#LOWEST} + 先判 {@code isCanceled}</b>：套壳记录被清掉的同时会把
     * 框架本体掉回世界；如果这次破坏随后被别的模组取消（方块其实还在），就等于凭空产出。
     * 放在最后一位 + 已取消即不做，才不会出现「方块还在、框架已经掉出来了」。</p>
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockBroken(final BlockEvent.BreakEvent event) {
        if (event.isCanceled()) {
            return; // 这次破坏已被取消（方块留着）：记录必须留着，也绝不能掉落
        }
        if (event.getLevel() instanceof final ServerLevel level) {
            RsccCableCuts.onBlockChanged(level, event.getPos());
            RsccSheaths.onBlockChanged(level, event.getPos());
        }
    }

    /**
     * 玩家放置 / 替换方块：同一位置重新放回线缆时，旧的断开 / 套壳记录必须先失效
     * （否则「放回来也连不上」、或者客户端会在一格新方块外面继续画壳）。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockPlaced(final BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled()) {
            return; // 放置被取消（这一格还是原来那根线缆）：记录必须留着
        }
        if (event.getLevel() instanceof final ServerLevel level) {
            RsccCableCuts.onBlockChanged(level, event.getPos());
            RsccSheaths.onBlockChanged(level, event.getPos());
        }
    }

    /** 爆炸摧毁方块：等同「被拆掉」，逐个受影响坐标清理断点与套壳记录。 */
    @SubscribeEvent
    public void onExplosion(final ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof final ServerLevel level) {
            for (final BlockPos pos : event.getAffectedBlocks()) {
                RsccCableCuts.onBlockChanged(level, pos);
                RsccSheaths.onBlockChanged(level, pos);
            }
        }
    }

    /**
     * <b>扳手拆除的收尾</b>：跑在整条右键链的<strong>最后</strong>（{@link EventPriority#LOWEST}），
     * 负责 RS / Create 那条「不触发 {@code BlockEvent.BreakEvent}」的拆除路径。
     *
     * <h2>为什么必须有这一步（用户：「破坏线缆之类的这种方块，它还是不会掉落」）</h2>
     * <p>被套住 / 被裹住的那一格仍然是 RS 的线缆（或 Create 的管道），玩家最自然的「破坏」方式就是
     * <b>潜行 + 扳手</b>。RS 的拆除实现是
     * {@code AbstractBaseBlock#dismantle}：{@code removeBlockEntity(pos)} + {@code setBlockAndUpdate(AIR)}
     * + <b>自己 new 一个 ItemEntity</b> 把方块吐回世界 —— 它<b>不会</b>走
     * {@code ServerPlayerGameMode#destroyBlock}，因此<b>不会</b>触发 {@link BlockEvent.BreakEvent}。
     * 而本模组的记录失效 / 掉落全部挂在 BreakEvent / EntityPlaceEvent / ExplosionEvent 上，
     * 于是「用扳手拆掉被套壳的线缆」会留下一条幽灵套壳记录，框架本体也永不掉落。
     * Create 的扳手拾取同理。</p>
     *
     * <p><b>判据为什么是「这一格已经变成空气」</b>：这一次右键链上只有「拆除」会把命中的那一格
     * 变成空气；「取下外壳 / 取下框架」两条路径都不动方块（它们只改坐标记录），
     * 因此这里的判断既不会误清记录，也不需要任何额外的状态传递。
     * 原方块自己由 RS / Create 的掉落逻辑负责（本类一行都不碰方块、不碰它们的掉落）。</p>
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRightClickBlockEnd(final PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof final ServerLevel level)) {
            return; // 状态改动一律在服务端（客户端只负责表现）
        }
        final BlockPos pos = event.getPos();
        if (!level.getBlockState(pos).isAir()) {
            return; // 方块还在：这次右键不是「拆除」，三条常规挂点与两个取下挂点已各自结算
        }
        // 与「玩家破坏 / 被替换 / 被炸掉」完全同一条清理链：只删记录 + 把框架本体原样掉回世界（守恒）
        RsccCableCuts.onBlockChanged(level, pos);
        RsccSheaths.onBlockChanged(level, pos);
        RsccCamouflage.onBlockChanged(level, pos);
    }

    private static void sync(final Player player) {
        if (player instanceof final ServerPlayer serverPlayer) {
            RsccCableCuts.syncTo(serverPlayer);
            // 套壳记录同样要在「登录 / 换维度 / 重生」时下发：否则客户端不知道哪些格子被套住，
            // 重算连接臂时会把断开「长回来」，也画不出那层壳。
            RsccSheaths.syncTo(serverPlayer);
        }
    }

    /**
     * 服务端执行一次切换。<b>不做任何过程性播报</b>（用户明确要求删掉「已断开 / 已恢复 /
     * 这里没有可断开的连接 / 该面已停止（恢复）自动连接」这些动作栏复述）：状态本身在世界上
     * 看得到（连接臂变化），成就由 {@link RsccCableCuts} 内部发出，这里只改状态。
     */
    private static void apply(final ServerLevel level, final BlockPos pos,
                              final Direction face, @Nullable final Direction seam) {
        final Mode mode = RsccCableCuts.modeFor(level);
        if (mode == Mode.SEAM) {
            // seam 为 null = 这一格没有任何可切的缝（选缝已经按 canToggleSeam 过滤过，
            // 只有「六邻都不是线缆、也没有连接臂」这种格才会走到这里）：什么都不做。
            if (seam == null) {
                return;
            }
            RsccCableCuts.toggleSeam(level, pos, seam);
        } else if (mode == Mode.FACE) {
            RsccCableCuts.toggleFace(level, pos, face);
        }
    }
}
