package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.common.Platform;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.support.network.NetworkNodeContainerProvider;
import com.refinedmods.refinedstorage.common.content.Sounds;
import com.simibubi.create.content.decoration.copycat.CopycatBlock;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「潜行 + 手持扳手右键 → 直接拆掉本模组的方块」。
 *
 * <h2>为什么做成事件挂点，而不是在方块里覆写 {@code useItemOn} / {@code useWithoutItem}</h2>
 * <p>因为 RS 自己就是这么做的 —— 本类是<b>与 RS 同源</b>的实现，不是「另一条看起来差不多的路」：</p>
 * <ul>
 *     <li>RS 的挂点是 NeoForge 的 {@code PlayerInteractEvent.RightClickBlock}
 *     （{@code refinedstorage/neoforge/ModInitializer.java:642-659} 的
 *     {@code registerWrenchingEvent}），处理器是
 *     {@code common/support/AbstractBaseBlock.java:141-247} 的
 *     {@code tryUseWrench → dismantleOrRotate → dismantle}；</li>
 *     <li>准入判据（RS 逐条同款）：手持物带 {@code c:tools/wrench} 标签
 *     （RS 在 {@code AbstractBaseBlock.java:47-50} 自己 {@code TagKey.create} 出这个 id，
 *     本模组直接用 NeoForge 常量 {@link Tags.Items#TOOLS_WRENCH}，两者是同一个标签）、
 *     {@code player.isCrouching()} 才拆（不潜行在 RS 那边是旋转，见下）、旁观者与
 *     「不能交互的位置」一律不接管；</li>
 *     <li>拆除动作（RS {@code AbstractBaseBlock.java:212-247}）：先造出「掉落用的那一份物品」，
 *     再移除方块，最后在命中点生成掉落物，成功时播 RS 自己的扳手音效
 *     （{@code Sounds.INSTANCE.getWrench()}，即 {@code refinedstorage:wrench}，
 *     见 {@code ModInitializer.java:872-875}）；</li>
 *     <li>接管方式（RS {@code ModInitializer.java:655-658}）：{@code setCanceled(true)} +
 *     {@code setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide()))}。</li>
 * </ul>
 *
 * <h2>为什么「潜行时不弹界面」是结构性保证，而不是靠某个方法里判手持物</h2>
 * <p>{@code ServerPlayerGameMode#useItemOn} 的顺序是：<b>先</b>发
 * {@code PlayerInteractEvent.RightClickBlock}（第 346 行），紧接着第 347 行
 * {@code if (event.isCanceled()) return event.getCancellationResult();} ——
 * 事件一旦被取消，后面的 {@code BlockState#useItemOn} → {@code BlockState#useWithoutItem}
 * → {@code ItemStack#useOn} <b>一句都不会执行</b>，界面自然打不开。</p>
 * <p>这正是本模组上一轮踩过的坑（在 {@code useWithoutItem} 里无条件消费动作 ⇒
 * {@code ItemStack#useOn} 永远轮不到）的根治写法：本类<b>一个字节都没有碰</b>任何方块的
 * {@code useItemOn} / {@code useWithoutItem}（自检脚本会断言这一点），
 * 因此既不可能抢掉物品的 {@code useOn}，也不可能让「不潜行的右键」改变行为
 * ——不潜行时本方法第二道判据就 {@code return}，一次都不取消，界面照开。</p>
 *
 * <h2>覆盖范围：本模组<b>全部</b>方块（判据 = 注册表命名空间）</h2>
 * <p>判据是 {@code BuiltInRegistries.BLOCK} 里这个方块的 id 属于本模组命名空间
 * （见 {@link #isDismantlable}）。这样做的好处是「将来新增的方块自动被覆盖」，
 * 不需要在每个方块类里各写一遍。当前覆盖的 14 个注册项：</p>
 * <ol>
 *     <li>{@code range_charger}、{@code quantity_keeper}、{@code advanced_quantity_keeper}、
 *     {@code schematic_loader}、{@code advanced_schematic_loader}、{@code sequence_pattern_terminal}、
 *     {@code sequence_assembly_executor}、{@code sequence_execution_chamber}、
 *     {@code unit_pattern_manager}、{@code collection_cache}、{@code intermediate_cache}
 *     —— 11 台机器（都带方块实体）；</li>
 *     <li>{@code separation_frame}、{@code infinite_separation_frame}（分隔框架，隐形方块）、
 *     {@code camouflage_frame}（伪装框架）—— 3 个框架方块（正常玩法下已不由物品放置，
 *     只服务旧存档 / 指令放出来的那一份，见 {@code SeparationFrameBlock} 与
 *     {@code CamouflageFrameBlock} 的类注释）。</li>
 * </ol>
 *
 * <h2>掉落与内容物：为什么不照抄 RS 的 {@code removeBlockEntity}</h2>
 * <p><b>RS 的做法</b>（{@code AbstractBaseBlock.java:228-245}）是：
 * {@code blockEntity.saveToItem(stack)} 把内容物塞进方块物品 NBT →
 * {@code level.removeBlockEntity(pos)}（注释写着 "Ensure that we don't drop items"）
 * → {@code setBlockAndUpdate(AIR)} → 自己 {@code new ItemEntity(...)} 吐出来。
 * 那句 {@code removeBlockEntity} 的作用是让随后的 {@code Block#onRemove} 里
 * {@code level.getBlockEntity(pos)} 变成 null，从而<b>跳过</b>方块自己的内容物结算。</p>
 * <p><b>照抄会坏在哪</b>：本模组每一个带内容的方块都在 {@code onRemove} 里调用
 * {@link BlockContentReleaser#release} —— 它负责「回网 / 爆出 / 存方块」三档策略，
 * 以及<b>机器集群把整份内容移交给仍在加载的幸存集群</b>
 * （{@code BlockContentReleaser.java:166-171}，注释写明必须发生在内容被写进方块物品之前，
 * 否则「重新放下时再并入一次 = 资源翻倍」）。把方块实体先删掉，这段结算整段被跳过：
 * 集群内容会同时留在掉落物 NBT 与幸存集群里，等于凭空翻倍。</p>
 * <p><b>因此本类改用「与原版挖掉这个方块同序」的两步</b>（{@link #dismantle}）：
 * 先 {@code Block.dropResources}（战利品表 + 各方块自己的 {@code getDrops} 策略），
 * 再 {@code level.removeBlock}（这一句触发 {@code onRemove}，方块实体仍在场，
 * {@link BlockContentReleaser} 与集群移交照常结算）。于是「挖掉它掉什么，扳手拆它就掉什么」，
 * 而拆除手感（无挖掘粒子、只播 RS 的扳手音效、潜行不弹界面、创造/生存一致）与 RS 一样。</p>
 *
 * <h2>多块结构 / 隐形框架的收尾</h2>
 * <ul>
 *     <li><b>执行仓成链</b>：链成员是「按邻接现算」出来的（{@code SequenceExecutionChamberBlockEntity}），
 *     没有任何需要手动注销的成员表；{@code level.removeBlock} 带 flags=3（通知邻块），
 *     {@code BlockEvent.NeighborNotifyEvent} 因此照常触发 →
 *     {@code RsccClusterInvalidation} / {@code RsccCacheInvalidation} 立刻作废集群与共享缓存池的解析缓存，
 *     最迟下一 tick 重算；</li>
 *     <li><b>机器集群</b>：内容移交由 {@code BlockContentReleaser.release} 在 {@code onRemove} 里做（见上）；</li>
 *     <li><b>隐形框架（分隔框架 / 伪装框架）</b>：它们的「套壳 / 裹壳」不是方块，而是挂在
 *     <b>被套那一格</b>上的坐标记录（{@code RsccSheaths} / {@code RsccCamouflage}）。
 *     拆掉框架方块后，那一格的记录清理由既有的收尾挂点负责
 *     （{@code RsccWrenchCableInteraction#onRightClickBlockEnd}，LOWEST 优先级，
 *     判据是「这一格已经变成空气」——本类拆完正是这个状态），框架本体该归还的也会归还，
 *     不会留下幽灵壳；</li>
 *     <li><b>伪装框架方块上的外壳材料</b>：{@code camouflage_frame} 的方块形态继承 Create 的伪装板
 *     （{@link CopycatBlock}），材料存在它的方块实体里（不是方块状态）。
 *     Create 既有的「潜行 + 扳手」语义里有一半是「归还材料」，本类既然接管了这次右键，
 *     就必须在掉落之前显式把材料还回去（{@link #refundCopycatMaterial}），
 *     否则材料会随方块一起消失 = 白丢玩家资源。这一条刻意<b>只借材料归还</b>：
 *     Create 的 {@code IWrenchable#onSneakWrenched} 在生存下把掉落塞进背包、创造下什么都不掉，
 *     模式不一致，与「和精致存储一样」的要求相反；</li>
 *     <li><b>无限分隔框架</b>：它自己的设计就是「拆下不掉落」（{@code SeparationFrameBlock.java:52}），
 *     而本类的掉落完全交给方块自己的战利品表，因此这里同样不掉 —— 忠实于该方块既有的语义，
 *     不是本类漏掉的一格。</li>
 * </ul>
 *
 * <h2>不做什么</h2>
 * <p>RS 的「<b>不</b>潜行 + 扳手 = 旋转 90°」这一档<b>刻意没有搬</b>：本模组的方块没有可旋转的
 * 朝向属性，而且用户明确要求保留既有交互（不潜行的右键仍然打开界面）。</p>
 */
public final class RsccWrenchDismantle {
    /** 与 RS 同源、且由 RS 自己的语言文件提供译文（中英都有）的权限提示键。 */
    private static final String KEY_NO_PERMISSION_DISMANTLE =
        "misc.refinedstorage.no_permission.build.dismantle";

    private static final Logger LOGGER = LoggerFactory.getLogger(RsccWrenchDismantle.class);
    /** 「RS 的扳手音效取不到」只提示一次（正常永远取得到）。 */
    private static final AtomicBoolean SOUND_WARNED = new AtomicBoolean();

    private RsccWrenchDismantle() {
    }

    /**
     * 注册到 NeoForge 全局事件总线。
     * <p>由 {@link RsccWrenchCableInteraction#register()} 在本类实例之后调用，
     * 因此本方法在 {@link EventPriority#HIGH} 这一档里排在
     * {@code RsccCamouflageInteraction} / {@code RsccSheathInteraction} /
     * {@code RsccWrenchCableInteraction} 三条既有挂点<b>之后</b>：
     * 「取下外壳 / 取下框架 / 断开线缆缝」永远先结算，本类只处理它们没接管的那些右键。</p>
     */
    public static void register() {
        NeoForge.EVENT_BUS.register(new RsccWrenchDismantle());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled()) {
            return; // 伪装外壳 / 分隔框架已经接管：一次右键只结算一层
        }
        final ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !stack.is(Tags.Items.TOOLS_WRENCH)) {
            return; // 准入①：手持物必须是扳手（与 RS 的 c:tools/wrench 是同一个标签）
        }
        final Player player = event.getEntity();
        if (!player.isShiftKeyDown()) {
            return; // 准入②：必须潜行 —— 不潜行的右键原样放行（界面照开，既有交互一字不动）
        }
        if (player.isSpectator() || !player.mayBuild()) {
            return; // 准入③：旁观者 / 冒险模式不生效（与 RS 的 isSpectator + mayBuild 同源）
        }
        final Level level = event.getLevel();
        final BlockPos pos = event.getPos();
        if (event.getFace() == null || !level.mayInteract(player, pos)) {
            return; // 准入④：没有命中面 / 这个位置不允许交互（出生点保护等）
        }
        final BlockState state = level.getBlockState(pos);
        if (!isDismantlable(state)) {
            return; // 不是本模组的方块：一行都不动，RS / Create / 原版照旧
        }
        // 到这里才接管：双端一致地取消事件 + 返回与 RS 相同的 SUCCESS（客户端据此挥手，
        // 且如类注释所述，后面的 useItemOn / useWithoutItem / useOn 整条链根本不会开始）。
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide()));
        if (level instanceof final ServerLevel serverLevel
            && player instanceof final ServerPlayer serverPlayer) {
            if (!mayDismantle(serverLevel, serverPlayer, pos, event.getFace())) {
                return; // 权限不足：RS 的提示已发出，这一次右键到此为止（照 RS 的做法吞掉动作）
            }
            dismantle(serverLevel, serverPlayer, pos, state, event.getHand(), event.getHitVec());
        }
    }

    /**
     * 这个方块是不是「本模组自己的、应当支持快速拆卸的方块」。
     *
     * <p><b>判据 = 注册表命名空间</b>：本模组的所有方块都注册在
     * {@link RS_Create_Compat#MODID} 命名空间下，因此这条判据天然覆盖全部 14 个注册项
     * （11 台机器 + 3 个框架方块），将来新增的方块也自动被覆盖，
     * 不需要（也不可能漏）在每个方块类里各写一份。反过来，RS 的线缆与 Create 的管道
     * 不在这个命名空间里，本类对它们一行都不改 —— 它们仍然由 RS / Create 自己的扳手逻辑处理。</p>
     */
    public static boolean isDismantlable(final BlockState state) {
        final Block block = state.getBlock();
        final net.minecraft.resources.ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id != null && RS_Create_Compat.MODID.equals(id.getNamespace());
    }

    /**
     * 权限判定：与 RS 的 {@code AbstractBaseBlock#dismantle} 逐条同款 ——
     * 拿这一格的 RS 网络节点容器（本模组的机器都注册了这个能力），
     * 容器说「不让建」就发 RS 自己那句提示并中止（拆不掉，但动作已被吞掉，不会去开界面）。
     */
    private static boolean mayDismantle(final ServerLevel level, final ServerPlayer player,
                                        final BlockPos pos, final Direction face) {
        final NetworkNodeContainerProvider provider = Platform.INSTANCE.getContainerProvider(level, pos, face);
        if (provider == null || provider.canBuild(player)) {
            return true;
        }
        RefinedStorageApi.INSTANCE.sendNoPermissionMessage(player,
            Component.translatable(KEY_NO_PERMISSION_DISMANTLE,
                level.getBlockState(pos).getBlock().getName()));
        return false;
    }

    /**
     * 服务端执行一次拆除：<b>先掉落、再移除</b>（与原版挖掉一个方块的顺序一致，
     * 也与 {@link BlockContentReleaser#release} 要求的前后关系一致 —— 见类注释的说明）。
     * 最后播 RS 自己的扳手音效，让手感与 RS 一致。
     */
    private static void dismantle(final ServerLevel level, final ServerPlayer player, final BlockPos pos,
                                  final BlockState state, final InteractionHand hand,
                                  final BlockHitResult hitResult) {
        refundCopycatMaterial(level, player, pos, state, hand, hitResult);
        final BlockEntity blockEntity = level.getBlockEntity(pos);
        // ① 掉落：走方块自己的战利品表与 getDrops 策略（内容物去向 / 集群移交在下一步的 onRemove 里结算）
        Block.dropResources(state, level, pos, blockEntity, player, ItemStack.EMPTY);
        // ② 移除方块：触发 onRemove（方块实体仍在场）→ 各机器的 BlockContentReleaser 照常结算；
        //    flags=3（isMoving=false）会通知邻块 ⇒ 集群 / 共享缓存池的解析缓存照常失效。
        //    ★ 这里刻意不调用 level.removeBlockEntity(pos)：那正是 RS 用来「跳过方块自己的内容物结算」的手法，
        //    对本模组会跳过「集群内容移交给幸存集群」，导致内容翻倍（见类注释）。
        if (!level.removeBlock(pos, false)) {
            // 理论上到不了（能通过 isDismantlable 的方块一定不是空气，removeBlock 必然成功）：
            // 只留一条痕迹，不改动任何状态。
            LOGGER.warn("Wrench dismantle could not remove the block at {} in {}", pos, level.dimension());
            return;
        }
        playWrenchSound(level, pos);
    }

    /**
     * 伪装框架的方块形态（{@link cretae.cookiewyq.rs_create_compat.block.CamouflageFrameBlock}）
     * 继承 Create 的伪装板 {@link CopycatBlock}，<b>外壳材料存在它的方块实体里</b>。
     * Create 既有的「潜行 + 扳手」语义里有一半是「把外壳材料还给你」
     * （{@code CopycatBlock#onWrenched}）；本类接管了整次右键，所以必须显式把这一半补上，
     * 否则材料会随方块一起消失 —— 那就是「拆了留下脏数据 / 白丢玩家资源」。
     *
     * <p><b>为什么只调 {@code onWrenched}、不调 {@code IWrenchable#onSneakWrenched}</b>：
     * 后者在<b>生存</b>下把掉落物直接塞进玩家背包、在<b>创造</b>下什么都不掉
     * （{@code IWrenchable.java:61-70} 的 {@code !player.isCreative()} 分支 + {@code destroyBlock(pos, false)}），
     * 两种模式不一致；而本类要的是与 RS 一致的「一律掉落成物品」。因此这里只借它「归还材料」那一步，
     * 掉落仍由本类的 {@link #dismantle} 统一负责。</p>
     *
     * <p>非伪装框架的方块不是 {@link CopycatBlock}，这里直接跳过（一个字节都不碰）。</p>
     */
    private static void refundCopycatMaterial(final ServerLevel level, final ServerPlayer player,
                                              final BlockPos pos, final BlockState state,
                                              final InteractionHand hand, final BlockHitResult hitResult) {
        if (!(state.getBlock() instanceof final CopycatBlock copycat) || level.getBlockEntity(pos) == null) {
            return;
        }
        copycat.onWrenched(state, new UseOnContext(player, hand, hitResult));
    }

    /**
     * 与 RS {@code AbstractBaseBlock#tryUseWrench} 完全相同的音效调用
     * （RS 自己的 {@code refinedstorage:wrench}，方块音源、1.0 音量 / 1.0 音高）。
     * <p>音效取不到时只是静默跳过：拆方块这件事不该因为一个音效资源而失败。
     * 注意这里<b>不</b>调用 {@code Level#levelEvent(2001)}（挖掘粒子与破坏音），
     * 因为 RS 的扳手拆除本来就没有那一下 —— 手感要与 RS 一致。</p>
     */
    private static void playWrenchSound(final ServerLevel level, final BlockPos pos) {
        try {
            level.playSound(null, pos, Sounds.INSTANCE.getWrench(), SoundSource.BLOCKS, 1.0F, 1.0F);
        } catch (final RuntimeException e) {
            if (SOUND_WARNED.compareAndSet(false, true)) {
                LOGGER.warn("Refined Storage wrench sound is unavailable; dismantle continues without it", e);
            }
        }
    }
}
