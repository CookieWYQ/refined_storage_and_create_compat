package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.CamouflageFrameItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * 「伪装框架」的交互挂点：<b>给已经裹住的格子选外观 / 把外壳取下来</b>，以及记录失效时的清理。
 *
 * <h2>为什么这里要单独挂事件（物品侧管不到的两件事）</h2>
 * <ol>
 *     <li><b>空手取下</b>：用户要的「潜行空手取下」根本不会经过物品的
 *     {@code Item#useOn}（手里没有东西），只能挂
 *     {@link PlayerInteractEvent.RightClickBlock}；</li>
 *     <li><b>套外壳材质</b>：用户要的「手持完整方块右键套上外壳」用的是<b>别的</b>物品
 *     （石头 / 玻璃 / 任何完整方块），物品侧同样管不到 —— 而它的判定必须完全复用 Create 伪装板的规则。</li>
 * </ol>
 * <p>伪装框架物品自己的 {@code useOn} 只负责「裹上 / 取下」那两种手持框架的情形
 * （见 {@code item/CamouflageFrameItem}），两者合起来才是完整的操作集合。</p>
 *
 * <h2>优先级：必须早于 RS 自己的扳手处理</h2>
 * <p>RS 对「自己的方块 + 扳手」有一段 {@code PlayerInteractEvent.RightClickBlock} 处理
 * （{@code AbstractBaseBlock#tryUseWrench}：不潜行 = 旋转、潜行 = <b>直接拆掉方块</b>）。
 * 被裹住的仍然是 RS 的线缆，所以「潜行 + 扳手取下外壳」如果不抢先取消事件，
 * 就会先被 RS 拆掉整根线缆。这里因此用 {@link EventPriority#HIGH}，先于 RS 的默认优先级处理。</p>
 *
 * <h2>服务端权威</h2>
 * <p>状态改动只在服务端（{@link RsccCamouflage}），客户端只取消事件 + 挥手。
 * 客户端看到的那层外壳<b>不需要</b>任何自建同步：材质是<b>被裹方块方块实体上的附件</b>，
 * 由 NeoForge 自己同步给观察者（{@code support/RsccCamouflageAttachment}），
 * 再配合一个「数据变了 → 重烘这一段网格」的极轻 S2C 信号（{@code network/RefreshCamouflagePacket}）。</p>
 *
 * <h2>2026-09-25 补：手势全集（用户第 6 条约定的朝向设计）</h2>
 * <ul>
 *     <li><b>手持「同一种」方块右键</b> → 外壳朝向旋转 90°（绕竖直轴；再点继续转）；
 *     <b>潜行 + 同一种方块</b> → 反向旋转（{@link #rotateShell}，不消耗物品）；</li>
 *     <li><b>手持「不同种」方块右键</b> → 换壳（旧壳原样归还，见 {@link #applyMaterial}）；
 *     潜行 + 不同种方块 → 不换（潜行这一档永远只做「反向 / 取下」两件事，避免误换外壳）；</li>
 *     <li><b>潜行 + 扳手</b> → <b>还有外壳可取时</b>只取外壳方块（框架本体保留，既有行为）；
 *     <b>没有外壳可取时</b>（空壳 / 已取过）不接管，把这次右键让给 RS / Create 自己的扳手拆除
 *     —— 见 {@link #onRightClickBlock} 的扳手分支与 {@code RsccWrenchCableInteraction#onRightClickBlockEnd}；</li>
 *     <li><b>潜行 + 空手 / 手持框架</b> → 取下整个伪装（框架 + 外壳一并归还，既有行为）。</li>
 * </ul>
 * <p>朝向只影响外观：改的是记录里的外壳材质方块状态，被裹方块自身一个字节都没动。</p>
 *
 * <h2>2026-09-26 修正：反馈只留「操作结果」（用户：「这些废话都不需要说」）</h2>
 * <p>上一版为了回应「没看见退还」，在取下时补了一句「创造模式没消耗、所以不退还」的说明。
 * 本轮用户明确否掉了这类文案：「取下来的时候也不需要说什么创造模式什么的、会消耗什么的、
 * 不返还什么的，这些废话都不需要说」。</p>
 * <p>因此：<b>归还动作照旧</b>（按记录里的守恒位 {@code frameConsumed} / {@code consumed} 执行，
 * 一进一出严格相抵），但<b>播报只留一句极简的操作结果</b>（「已取下伪装」/「已取下外壳方块」）。
 * 失败与前置原因仍然保留（例如「这一格还没有外壳方块」「只支持线缆与流体管道」）——
 * 那些是玩家看懂「为什么没反应」所必需的，与收支解释不是一回事。</p>
 */
public final class RsccCamouflageInteraction {
    /** 外壳已套上。 */
    private static final String KEY_SHELLED = "block.rs_create_compat.camouflage_frame.hint.shelled";
    /** 已经有外壳了（同一种材质，不重复消耗）。 */
    private static final String KEY_OCCUPIED = "block.rs_create_compat.camouflage_frame.hint.occupied";
    /** 外壳取下了（与物品侧取下共用同一句反馈）。 */
    private static final String KEY_TAKEN_OFF = "block.rs_create_compat.camouflage_frame.hint.unwrapped";
    /** 已经裹着：提示潜行右键可取下（不潜行时空手 / 扳手右键给出的提示）。 */
    private static final String KEY_TAKE_OFF_HINT = "block.rs_create_compat.camouflage_frame.hint.take_off_hint";
    /** 只取下了外壳方块、框架本体保留着（潜行 + 扳手，本轮新增的「取回内层方块」路径）。 */
    private static final String KEY_SHELL_REMOVED = "block.rs_create_compat.camouflage_frame.hint.shell_removed";
    /** 这一格还没选过外壳，没有可取下的方块。 */
    private static final String KEY_NO_SHELL = "block.rs_create_compat.camouflage_frame.hint.no_shell";
    /** 外壳朝向已旋转 90°（手持「同一种」方块右键；潜行 = 反向）。 */
    private static final String KEY_ROTATED = "block.rs_create_compat.camouflage_frame.hint.rotated";

    private RsccCamouflageInteraction() {
    }

    /** 注册到 NeoForge 全局事件总线（由主类构造函数调用一次）。 */
    public static void register() {
        NeoForge.EVENT_BUS.register(new RsccCamouflageInteraction());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled()) {
            return; // 已经被更外层处理过：一次右键只处理一层
        }
        final Level level = event.getLevel();
        final BlockPos pos = event.getPos();
        final Player player = event.getEntity();
        if (!RsccCamouflage.isCamouflaged(level, pos)) {
            return; // 没裹住：完全交给原版 / 物品自己的 useOn（「裹上」那一路在那里）
        }
        final ItemStack stack = event.getItemStack();
        final boolean sneaking = player.isShiftKeyDown();
        if (sneaking && isTakeOffTool(stack)) {
            // 潜行右键 = 取下（与分隔框架同一套约定：避免随手一右键就把壳摘了）。
            // 手持什么都可以（空手 / 框架本体），但别的工具不参与，免得挡住其它模组的潜行交互
            // —— 注意「潜行 + 手持同一种外壳方块」不在这里拦：那一路是下面的「反向旋转外壳朝向」。
            if (stack.is(Tags.Items.TOOLS_WRENCH)) {
                // 2026-09-26 修正（用户：「破坏线缆之类的这种方块，它还是不会掉落」）：
                // 扳手是 RS / Create 自己的「拆除 / 拾取」工具，潜行 + 扳手在它们那儿的语义是
                // 「拆掉这个方块并把它捡回来」。因此这里分成两支：
                //   ① 这一格还有外壳可取 → 仍然只取外壳（保留框架与方块，既有语义不变）；
                //   ② 没有外壳可取（还没选过外观的「空壳」/ 已经取过）→ 不接管，
                //      把这次右键完整让给 RS / Create 的拆除；套壳记录与框架本体由
                //      RsccWrenchCableInteraction#onRightClickBlockEnd（LOWEST，跑在拆除之后）收尾。
                if (!RsccCamouflage.hasMaterial(level, pos)) {
                    return;
                }
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                if (level instanceof final ServerLevel serverLevel) {
                    takeOffShell(serverLevel, player, pos);
                }
                return;
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            if (level instanceof final ServerLevel serverLevel) {
                takeOff(serverLevel, player, pos);
            }
            return;
        }
        // 不潜行 + 手持完整方块 = 给它套上 / 换一个外壳（判定与 Create 伪装板完全同源，见 readMaterial）
        if (!player.mayBuild()) {
            return;
        }
        final BlockState material = readMaterial(level, pos, stack, event.getFace());
        if (material == null) {
            // 空手 / 扳手右键一个已裹住的格子：说一句「怎么取下」，否则玩家只会觉得「点了没反应」。
            // 手持扳手且这一次会被「扳手断缝」接管时不打扰：那时这次右键的语义是断开 / 恢复这一道缝，
            // 由 RsccWrenchCableInteraction 给出它的反馈（一次右键只对应一个反馈）。
            if (!sneaking && (stack.isEmpty() || stack.is(Tags.Items.TOOLS_WRENCH))
                && !wrenchSeamGesture(level, pos, stack) && !level.isClientSide()) {
                player.displayClientMessage(Component.translatable(KEY_TAKE_OFF_HINT), true);
            }
            return;
        }
        final RsccCamouflage.Camo current = RsccCamouflage.get(level, pos);
        final boolean sameShell = current != null && current.material() != null
            && current.material().is(material.getBlock());
        if (sneaking && !sameShell) {
            // 潜行是「精确 / 反向」档（用户第 6 条只给了它两个语义：同种方块 = 反向转壳、
            // 扳手 = 只取外壳、空手 / 框架 = 取下整个伪装），因此潜行 + 另一种方块不换壳：
            // 玩家想换壳直接（不潜行）右键即可，这样「潜行右键」永远不会意外换掉外壳。
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (level instanceof final ServerLevel serverLevel) {
            if (sameShell) {
                // 手持「同一种」方块 = 旋转外壳朝向（潜行 = 反向），不消耗物品、不改被裹方块的状态
                rotateShell(serverLevel, player, pos, !sneaking);
            } else {
                applyMaterial(serverLevel, player, pos, material, stack);
            }
        }
    }

    /** 取下时允许手持的三类物品：空手 / 伪装框架本体 / 任何扳手（其余工具不参与）。 */
    private static boolean isTakeOffTool(final ItemStack stack) {
        return stack.isEmpty()
            || stack.is(RS_Create_Compat.CAMOUFLAGE_FRAME_ITEM.get())
            || stack.is(Tags.Items.TOOLS_WRENCH);
    }

    /**
     * 这次右键会不会由「扳手断缝」结算（手里是扳手 + 档位不是 OFF + 目标是 RS 线缆族）——
     * 是的话本次右键的语义就是「断开 / 恢复这一道缝」，本类不该再叠一句自己的提示。
     *
     * <p>判据与 {@code RsccWrenchCableInteraction#onRightClickBlock} 的准入同源（那一条还额外要求
     * 「没潜行 + 有建造权限」，本方法只用它的三个与「提示要不要说话」相关的子条件），
     * {@code RsccSheathInteraction} 里有一份<b>逐字相同</b>的同名方法；
     * 自检脚本会把两份方法体文本直接比对，因此不会悄悄漂移。</p>
     */
    private static boolean wrenchSeamGesture(final Level level, final BlockPos pos, final ItemStack stack) {
        return stack.is(Tags.Items.TOOLS_WRENCH)
            && RsccCableCuts.modeFor(level) != RsccCableCuts.Mode.OFF
            && RsccWireBlocks.isWire(level.getBlockState(pos));
    }

    /**
     * 这一件手持物品在当前命中面上能当外壳用吗？能就返回「该方块状态」（朝向等已按命中面摆好）。
     *
     * <p><b>为什么直接问 Create</b>：判定完全落在
     * {@code CopycatBlock#getAcceptedBlockState}（数据包标签 → 方块实体 → 楼梯 → 整格外形 / 碰撞非空），
     * 也就是「伪装板收什么，伪装框架就收什么」，本模组不另立一份口径
     * （{@code CamouflageFrameBlock} 继承的就是 Create 的 {@code CopycatBlock}）。</p>
     */
    private static BlockState readMaterial(final Level level, final BlockPos pos, final ItemStack stack,
                                           @org.jetbrains.annotations.Nullable final net.minecraft.core.Direction face) {
        if (!(stack.getItem() instanceof BlockItem)) {
            return null;
        }
        return RS_Create_Compat.CAMOUFLAGE_FRAME_BLOCK.get().getAcceptedBlockState(level, pos, stack, face);
    }

    /**
     * 服务端：换 / 装外壳（不销毁物品：换外壳时旧外壳原样归还）。
     *
     * <p><b>为什么这里不再有「同一种外壳」的分支</b>：同种方块右键的语义本轮改成了
     * <b>旋转外壳朝向</b>（见 {@link #rotateShell}，用户第 6 条），调用方在进来之前就已分流，
     * 因此本方法只管「换成另一个方块的外壳」——把两种手势各自的一条路径写在一处，
     * 就不会出现「既想转又换」的模糊分支。{@code frameConsumed} 位不参与换壳（框架本体没动）。</p>
     */
    private static void applyMaterial(final ServerLevel level, final Player player, final BlockPos pos,
                                      final BlockState material, final ItemStack stack) {
        final RsccCamouflage.Camo current = RsccCamouflage.get(level, pos);
        // 创造模式不扣物品，也<b>不记录</b>被消耗物品 —— 否则拆掉时会凭空掉出一个方块（复制漏洞）。
        final ItemStack consumed = player.hasInfiniteMaterials() ? ItemStack.EMPTY : stack.copyWithCount(1);
        RsccCamouflage.setMaterial(level, pos, material, consumed);
        if (current != null && !current.consumed().isEmpty()) {
            CamouflageFrameItem.give(player, current.consumed().copy()); // 换外壳：旧外壳原样归还
        }
        if (!player.hasInfiniteMaterials()) {
            stack.consume(1, player);
        }
        player.displayClientMessage(Component.translatable(KEY_SHELLED), true);
    }

    /**
     * 服务端：<b>把外壳朝向旋转 90°</b>（用户第 6 条：手持「同一种」方块右键 = 转 90°，潜行 = 反向）。
     *
     * <p><b>为什么不消耗物品、也不碰被裹方块</b>：旋转的是「记录里的外壳材质方块状态」
     * （{@link RsccCamouflage#rotateMaterial}），而外壳只是画上去的一层外观 ——
     * 被裹方块的方块状态、方块实体、能力一个字节都不动（用户要求「朝向必须只影响外观」）。
     * 材质本身随记录写进存档，因此重登后朝向仍然保留。</p>
     *
     * <p><b>没有朝向可转时</b>（石头、玻璃一类没有朝向属性的方块，{@code rotate} 返回原状态）：
     * 只给一句提示，绝不假装转过了，也绝不动任何物品。</p>
     */
    private static void rotateShell(final ServerLevel level, final Player player, final BlockPos pos,
                                    final boolean clockwise) {
        final BlockState rotated = RsccCamouflage.rotateMaterial(level, pos, clockwise);
        player.displayClientMessage(Component.translatable(rotated == null ? KEY_OCCUPIED : KEY_ROTATED), true);
    }

    /** 服务端：取下外壳（归还框架本体 + 已用掉的外壳方块，创造模式下当初没扣、这里也就不归还）。 */
    private static void takeOff(final ServerLevel level, final Player player, final BlockPos pos) {
        final RsccCamouflage.Camo removed = RsccCamouflage.remove(level, pos);
        if (removed == null) {
            return; // 极罕见的竞态：记录刚被清掉，不改动任何物品
        }
        // 只归还「当初真的扣过」的东西（守恒）；归还结果不再播报 ——
        // 用户明确要求删掉「创造模式 / 是否消耗 / 不返还」这类解释性废话，只留一句极简结果。
        if (removed.frameConsumed()) {
            CamouflageFrameItem.give(player, new ItemStack(RS_Create_Compat.CAMOUFLAGE_FRAME_ITEM.get()));
        }
        if (!removed.consumed().isEmpty()) {
            CamouflageFrameItem.give(player, removed.consumed().copy());
        }
        player.displayClientMessage(Component.translatable(KEY_TAKEN_OFF), true);
    }

    /**
     * 服务端：<b>只取下外壳方块</b>（框架本体保留）。
     *
     * <p><b>用户第 4 条的「取回内层方块」</b>：原话「我怎么把里面的方块拿回来？现在好像没有让它掉落的
     * （方式）。比如我想换方块了怎么办？我就非得破坏掉然后再放吗？」。上一版只有一个「潜行右键」
     * 的粗粒度入口，它会把框架与外壳<b>一起</b>取下——想单独拿回/换掉那个方块就必须先把整个伪装拆掉
     * 再装回来。这里把两件事拆开：潜行 + 扳手只把外壳方块还给你，那一格依旧是已伪装状态
     * （外观退回不透视的「空壳」兜底模型），框架本体与它的记录一个字节都没动。</p>
     *
     * <p>没选过外壳（空壳）时只给一句提示，绝不凭空产出任何物品；外壳是在创造模式套上的
     * （记录里 {@code consumed} 为空）时同样不归还。</p>
     */
    private static void takeOffShell(final ServerLevel level, final Player player, final BlockPos pos) {
        final ItemStack refund = RsccCamouflage.clearMaterial(level, pos);
        if (refund == null) {
            player.displayClientMessage(Component.translatable(KEY_NO_SHELL), true);
            return;
        }
        final boolean refunded = !refund.isEmpty();
        if (refunded) {
            CamouflageFrameItem.give(player, refund.copy());
        }
        // 只留一句极简结果：创造模式当初没消耗（consumed 为空）→ 不再解释「为什么不退」（用户要求删掉这类废话）
        player.displayClientMessage(Component.translatable(KEY_SHELL_REMOVED), true);
    }

    // ------------------------------------------------------------------
    // 记录失效：拆掉 / 被替换 / 被炸掉（把框架与外壳掉回去）
    // ------------------------------------------------------------------

    /**
     * 玩家破坏方块：把框架本体 + 已使用的外壳方块原样掉回世界。
     *
     * <p><b>为什么是 {@link EventPriority#LOWEST} + 先判 {@code isCanceled}</b>：这两条一起才守得住
     * 「绝不复制物品」——掉落动作一旦执行，物品就已经产出；若这次破坏随后被别的模组
     * （保护插件一类）取消，方块其实还在，那就成了凭空产出。放在最后一位处理 + 已取消即不做，
     * 于是「只在这一格真的会被拆掉时」才产出。</p>
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockBroken(final BlockEvent.BreakEvent event) {
        if (event.isCanceled()) {
            return; // 这次破坏已被取消（方块留着）：绝不能掉落，否则同一份物品被复制出来
        }
        if (event.getLevel() instanceof final ServerLevel level) {
            RsccCamouflage.onBlockChanged(level, event.getPos());
        }
    }

    /** 玩家放置 / 替换方块：同一位置换上新方块时，旧的伪装记录必须先失效（同样只在真的放置成功时做）。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockPlaced(final BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled()) {
            return; // 放置被取消（原来那一格还是老方块）：记录必须留着
        }
        if (event.getLevel() instanceof final ServerLevel level) {
            RsccCamouflage.onBlockChanged(level, event.getPos());
        }
    }

    @SubscribeEvent
    public void onExplosion(final ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof final ServerLevel level) {
            for (final BlockPos pos : event.getAffectedBlocks()) {
                RsccCamouflage.onBlockChanged(level, pos);
            }
        }
    }

    // ------------------------------------------------------------------
    // 快照下发：登录 / 换维度 / 重生
    // ------------------------------------------------------------------

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

    /**
     * 区块刚发给某个玩家：把这一块里所有被裹格的「请重烘」信号补给他。
     *
     * <p><b>为什么需要这一条</b>：外壳材质由 NeoForge 的附件同步随区块自动下发，但那份数据
     * <b>只会被塞回客户端方块实体、不会重烘网格</b>，而外壳的几何是<b>烘焙产物</b>。
     * 新区块的烘焙时机与附件包到达时机是两个独立时间点，烘焙一旦先跑，这一块就会一直看不到外壳。
     * 这里在「区块发给玩家」这个确定的时间点上补一次重烘，把竞态消掉
     * （细节见 {@link RsccCamouflageAttachment#refreshChunkFor}）。</p>
     */
    @SubscribeEvent
    public void onChunkSent(final net.neoforged.neoforge.event.level.ChunkWatchEvent.Sent event) {
        RsccCamouflageAttachment.refreshChunkFor(event.getPlayer(), event.getChunk());
    }

    private static void sync(final Player player) {
        if (player instanceof final ServerPlayer serverPlayer) {
            // 外壳材质<b>不需要</b>在这里下发：它是被裹方块方块实体上的附件，由 NeoForge 自己在
            // 「区块被观察 / 附件被改动」时同步给客户端（见 support/RsccCamouflageAttachment）。
            // 「隐藏已填充方块」是按玩家的全局开关（用户第 2 条），因此单独发一次；
            // 它与逐格数据同一时机（登录 / 换维度 / 重生），保证重登后看到的显示状态与关闭游戏前一致。
            RsccCamouflage.syncHiddenTo(serverPlayer);
        }
    }
}
