package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.item.SeparationFrameItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 「分隔框架套壳」的全局交互挂点：<b>潜行右键把壳取下来并回收框架本体</b>。
 *
 * <h2>为什么物品路径之外还要再挂一个事件（用户第 4 条反馈的根因）</h2>
 * <p>用户原话：「分隔框架到底该怎么回收？现在你没有给我任何回收方式，这一轮必须给我做。」
 * 上一版把「取下」只做在 {@link SeparationFrameItem#useOn} 里（必须手持框架本体、且必须潜行），
 * 于是玩家手里一空就完全没有回收入口，而「空手取下」「扳手取下」这两件<b>根本不经过物品</b>的操作
 * 更是无路可走；更糟的是被套住的那一格仍然是 RS 的线缆，<b>潜行 + 扳手</b>会先被 RS 自己的
 * {@code AbstractBaseBlock#tryUseWrench} 拿去「拆掉整根线缆」——玩家想取下壳，结果丢了线缆。</p>
 * <p>因此本轮补上一条与伪装框架完全对称的全局挂点（{@code RsccCamouflageInteraction} 同一个做法）：</p>
 * <ul>
 *     <li><b>潜行 + 右键</b>（空手 / 分隔框架本体）→ 取下那一格的套壳并归还框架本体
 *     （<b>保留被套的那个方块</b>）；</li>
 *     <li><b>不潜行 + 空手或扳手</b> → 只在动作栏说一句「潜行右键可取下」，避免玩家以为右键没反应。</li>
 * </ul>
 *
 * <h2>2026-09-26 修正：<b>潜行 + 扳手不再被本类接管</b>（用户：「破坏线缆之类的这种方块，它还是不会掉落」）</h2>
 * <p>用户原话：「我<b>破坏</b>线缆之类的这种方块，它<b>还是不会掉落</b>这些方块和这个框架（伪装框架）」。
 * 根因就在这一档：扳手是 <b>RS / Create 自己的拆除工具</b>，而被套住的那一格仍然是 RS 的线缆
 * （或 Create 的管道）—— 玩家「用扳手拆掉这根线缆」是最自然的操作。此前本类把「潜行 + 扳手」
 * 整个接管（取消事件 → 只取下套壳），于是：</p>
 * <ol>
 *     <li>RS 的 {@code AbstractBaseBlock#tryUseWrench → dismantle}（「拆除并拾取这根线缆」）
 *     根本轮不到执行 → <b>原方块永远不掉落</b>；</li>
 *     <li>而 RS 的拆除是「直接 {@code setBlockAndUpdate(AIR)} + 自建 ItemEntity」，<b>不触发</b>
 *     {@code BlockEvent.BreakEvent} → 我们的清理 / 掉落挂点也不会被调用（记录残留、框架不掉）；</li>
 *     <li>Create 的扳手拾取同理。</li>
 * </ol>
 * <p>修法（两件事一起做）：</p>
 * <ul>
 *     <li><b>扳手这一档彻底让出去</b>：{@link #isTakeOffTool} 里<b>不再包含扳手</b>，
 *     于是「潜行 + 扳手」判完就 {@code return}，一次都不取消 —— RS / Create 的拆除照常发生，
 *     原方块按它们自己的规则掉落；</li>
 *     <li><b>拆除后的收尾有专门挂点</b>：{@code RsccWrenchCableInteraction#onRightClickBlockEnd}
 *     （{@link EventPriority#LOWEST}，跑在 RS 的拆除之后）看到「这一格已经变成空气」时，
 *     补上 {@code RsccSheaths.onBlockChanged}：清掉套壳记录 + 把框架本体掉回世界（守恒）。
 *     等价于「方块被拆掉」那三挂点，只是把 RS 那条不触发事件的拆除路径也覆盖上。</li>
 * </ul>
 * <p>「取下套壳但保留这个方块」这一用途仍然完整保留：潜行 + 空手 / 潜行 + 手持任一种分隔框架本体。</p>
 *
 * <h2>优先级：必须早于 RS 自己的扳手处理</h2>
 * <p>RS 对「自己的方块 + 扳手」有一段 {@code PlayerInteractEvent.RightClickBlock} 处理
 * （{@code AbstractBaseBlock#tryUseWrench}：不潜行 = 旋转、潜行 = <b>直接拆掉方块</b>）。
 * 被套住的仍然是 RS 的线缆，所以「潜行 + 空手 / 手持框架取下」如果不抢先取消事件，
 * 就会被 RS 按「拆掉整根线缆」处理（还会连带丢掉里面的网络状态）。
 * 这里因此用 {@link EventPriority#HIGH}，先于 RS 的默认优先级处理 —— 与
 * {@code RsccCamouflageInteraction} 同一条理由。</p>
 *
 * <h2>与伪装框架的分工</h2>
 * <p>同一格可以既套着分隔框架、又裹着伪装外壳，此时<b>伪装外壳优先</b>（它是外面那一层）：
 * 本类见到 {@code RsccCamouflage.isCamouflaged} 或事件已被更高优先级取消就直接退出，
 * 把这次右键完整让给 {@code RsccCamouflageInteraction}。于是「先用几次潜行右键把外壳取掉、
 * 再取分隔框架」是一条确定的顺序，不会一次点掉两层。</p>
 *
 * <h2>服务端权威 + 守恒</h2>
 * <p>状态改动只在服务端（{@link RsccSheaths#remove}），归还判定与「背包满则掉在脚下」复用
 * {@link RsccSheaths#refund}（与物品侧同一份实现）；客户端只取消事件 + 挥手。</p>
 */
public final class RsccSheathInteraction {
    /** 已套住、给出取下方法的提示（不潜行时空手 / 扳手右键）。 */
    private static final String KEY_TAKE_OFF_HINT = "message.rs_create_compat.frame_take_off_hint";
    /** 已取下（与物品侧共用同一句反馈）。 */
    private static final String KEY_UNSHEATHED = "message.rs_create_compat.frame_unsheathed";

    private RsccSheathInteraction() {
    }

    /** 注册到 NeoForge 全局事件总线（由主类构造函数调用一次）。 */
    public static void register() {
        NeoForge.EVENT_BUS.register(new RsccSheathInteraction());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled()) {
            return; // 已经被更外层（伪装外壳）接管：一次右键只处理一层
        }
        final Level level = event.getLevel();
        final BlockPos pos = event.getPos();
        if (!RsccSheaths.isSheathed(level, pos) || RsccCamouflage.isCamouflaged(level, pos)) {
            return; // 没套住 / 外面还裹着伪装：完全交给原版 / 物品自己的 useOn
        }
        final Player player = event.getEntity();
        final ItemStack stack = event.getItemStack();
        if (!player.isShiftKeyDown()) {
            // 不潜行只给一句提示：否则玩家会以为右键对套住的格子没反应。
            // 手持别的方块时不打扰（那多半是想给伪装框架换外壳，与本类无关）；
            // 这一次会被「扳手断缝」接管时也不说话（那一次右键的语义是断开 / 恢复这一道缝）。
            if ((stack.isEmpty() || stack.is(Tags.Items.TOOLS_WRENCH))
                && !wrenchSeamGesture(level, pos, stack) && !level.isClientSide()) {
                player.displayClientMessage(Component.translatable(KEY_TAKE_OFF_HINT), true);
            }
            return;
        }
        if (!isTakeOffTool(stack)) {
            return; // 其余工具不参与，免得挡住别的模组的潜行交互
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (level instanceof final ServerLevel serverLevel) {
            takeOff(serverLevel, player, pos);
        }
    }

    /** 取下时允许手持的两类物品：空手 / 任一种分隔框架本体。 */
    private static boolean isTakeOffTool(final ItemStack stack) {
        // 刻意<b>不含扳手</b>：扳手是 RS / Create 自己的「拆除 / 拾取」工具，潜行 + 扳手必须完整让给它们，
        // 否则「用扳手拆掉这根线缆」这条最自然的操作会被本类吞掉（原方块与框架都不掉落，见类注释）。
        return stack.isEmpty() || stack.getItem() instanceof SeparationFrameItem;
    }

    /**
     * 这次右键会不会由「扳手断缝」结算（手里是扳手 + 档位不是 OFF + 目标是 RS 线缆族）——
     * 是的话本次右键的语义就是「断开 / 恢复这一道缝」，本类不该再叠一句自己的提示。
     *
     * <p>判据与 {@code RsccWrenchCableInteraction#onRightClickBlock} 的准入同源（那一条还额外要求
     * 「没潜行 + 有建造权限」，本方法只用它的三个与「提示要不要说话」相关的子条件），
     * {@code RsccCamouflageInteraction} 里有一份<b>逐字相同</b>的同名方法；
     * 自检脚本会把两份方法体文本直接比对，因此不会悄悄漂移。</p>
     */
    private static boolean wrenchSeamGesture(final Level level, final BlockPos pos, final ItemStack stack) {
        return stack.is(Tags.Items.TOOLS_WRENCH)
            && RsccCableCuts.modeFor(level) != RsccCableCuts.Mode.OFF
            && RsccWireBlocks.isWire(level.getBlockState(pos));
    }

    /**
     * 服务端：取下套壳（含快照）并按记录里的守恒位归还框架本体。
     *
     * <p><b>为什么只有一句反馈</b>：用户明确要求「取下来的时候也不需要说什么创造模式什么的、
     * 会消耗什么的、不返还什么的，这些废话都不需要说」。因此这里只保留一句极简的操作结果；
     * 归还与否完全由 {@link RsccSheaths#refund} 按记录里的 {@code paid} 位决定（守恒不受影响），
     * 不再向玩家解释收支原因。</p>
     */
    private static void takeOff(final ServerLevel level, final Player player, final BlockPos pos) {
        final RsccSheaths.SheathRemoval removed = RsccSheaths.remove(level, pos);
        if (removed == null) {
            return; // 极罕见的竞态：记录刚被清掉，不改动任何物品
        }
        RsccSheaths.refund(player, removed);
        player.displayClientMessage(Component.translatable(KEY_UNSHEATHED), true);
    }
}
