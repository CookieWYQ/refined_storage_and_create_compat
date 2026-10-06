package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.item.CamouflageFrameItem;
import cretae.cookiewyq.rs_create_compat.item.SeparationFrameItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「连锁套壳 / 连锁取回」的唯一实现：<b>只在 FTB Ultimine 的连锁模式下发生</b>，
 * 普通右键永远只作用于命中那一格。
 *
 * <h2>为什么「批量」只能由 FTB 的连锁键触发（本轮纠正的根因）</h2>
 * <p>用户原话：「我的意思不是指直接让他批量套壳，我的意思指的是<b>在连锁模式下就是 FTB 的连锁模式下</b>
 * 进行批量套上这个伪装框架」「我并不是想让直接让空手套壳」。上一版把<b>普通右键</b>直接改成
 * 「沿视线方向自动连套一片」，等于把两个概念揉在了一起 —— 玩家只是想像放方块一样点一下，
 * 结果一次点掉一整段管线，而且空手 / 手滑也会触发。因此本轮：</p>
 * <ul>
 *     <li><b>默认手势收回「只作用于命中那一格」</b>（{@code item/SeparationFrameItem}、
 *     {@code item/CamouflageFrameItem} 不再调用本类的任何方法）；</li>
 *     <li><b>批量只保留这一条入口</b>：{@link #chainSheath} —— 它由 {@link RsccUltimineIntegration}
 *     挂在 FTB Ultimine 的右键连锁 API（{@code dev.ftb.mods.ftbultimine.api.rightclick}）上，
 *     玩家<b>按住连锁键</b>右键时才会被 FTB 调用。没装 FTB Ultimine 时这条入口根本不存在，
 *     行为 = 单格套壳（不报错、不静默失效）。</li>
 * </ul>
 *
 * <h2>两种框架共用这一条入口与同一套规则</h2>
 * <p>分隔框架与伪装框架在「连锁」这件事上概念完全一致（用户原话「伪装框也是一样的」），
 * 差别只有两点：状态写进哪份记录（{@link RsccSheaths} / {@link RsccCamouflage}）、
 * 成功反馈用哪句话。这两点被 {@link FrameKind} 收成两个枚举常量，
 * 而「套哪几格」（{@link #plan} + {@link #expand}）与「怎么落库 + 怎么扣物品」（{@link #apply}）
 * 只有一份实现 —— 两个框架的连锁规则因此不可能漂移。</p>
 *
 * <h2>候选集合怎么来（范围与上限一律沿用 FTB Ultimine 自己的设置）</h2>
 * <ol>
 *     <li><b>FTB 给出的候选坐标</b>：{@code ShapeContext} 的那一份 {@code Collection<BlockPos>}
 *     就是 FTB 用「当前连锁形状 + 它自己的 {@code max_blocks} 配置」算好的（它的
 *     {@code RightClickDispatcher} 把 {@code FTBUltiminePlayerData#cachedPositions()} 直接传进来），
 *     本模组<b>直接用</b>，不另算一份范围；</li>
 *     <li><b>再补「与目标格相连的同族方块」</b>：FTB 的形状是按<b>方块状态等价</b>匹配的，
 *     同一条管线里跨颜色 / 跨窗口状态的同类方块可能被它漏掉，而用户要的是
 *     「同类的这个线缆、管道之类的东西同时给它套上」，因此这里从目标格出发再做一次同族邻接 BFS
 *     把整段补齐；</li>
 *     <li><b>上限</b>：两支候选合起来截断到 {@code ShapeContext#maxBlocks()}
 *     —— 那正是 FTB 对这名玩家生效的上限（含它的 ranks 节点与属性修正），本模组不另立数值。</li>
 * </ol>
 *
 * <p><b>为什么 BFS 不看「连接是否被断」</b>：这里要的是「物理上连成一段的同族方块」。
 * 已经套住的格子本来就会被幂等跳过，把既有套壳（它会把连接冻结在套上那一刻）当成边界
 * 只会让「整条链一起套上」这件事变得不可预测，因此 BFS 只按邻接 + 同族展开。</p>
 *
 * <h2>硬约束（逐条落在实现里）</h2>
 * <ul>
 *     <li><b>不搬迁任何内容</b>：套壳只调用 {@link RsccSheaths#addAll} / {@link RsccCamouflage#addAll}，
 *     取回只调用 {@link RsccSheaths#removeAll} / {@link RsccCamouflage#removeAll}（都只改坐标记录）
 *     与 {@link SeparationFrameGuard}，<b>不放置 / 不销毁方块、不碰方块实体、
 *     不读写流体或任何库存</b> —— 管道里的流体、线缆上的网络原样不动；</li>
 *     <li><b>服务端权威</b>：只接受 {@link ServerPlayer}（FTB 也只在服务端分发右键连锁），
 *     客户端一行状态都不写；</li>
 *     <li><b>幂等</b>：已有壳 / 已裹住的格子跳过，不重复消耗、不重复刷新；</li>
 *     <li><b>消耗规则</b>：无限版与创造模式不消耗（{@link ItemStack#consume} 本身也会在创造模式下
 *     不消耗）；普通版按<b>真正新增的格数</b>逐个消耗，手里不够时<b>只套得起的那些</b>
 *     （先套近的、即目标格与 FTB 给出的顺序），绝不会出现「套上了但没扣」或「扣了但没套」；</li>
 *     <li><b>批量</b>：整批只走一次 {@code addAll}（一次快照广播 + 一次去重后的连接刷新），
 *     绝不逐格发包；每一格的连接快照都由 {@link RsccSheaths#addAll} 在「任何记录写入之前」统一算好，
 *     因此整批相当于冻结了同一份「套上前」的世界状态，不会出现「前几格的写入污染后面格的快照」。</li>
 *     <li><b>取回（本轮新增）</b>：目标格已套住 / 已裹住，或玩家潜行时，同一条连锁链路改为<b>整段取回</b>；
 *     归还判定完全落在每一格自己的记录上（普通版当初扣过才还、无限版与创造模式不产出），
 *     因此「取回多少格就归还多少个普通框架」与单格路径逐字一致；</li>
 *     <li><b>不显示任何统计</b>：用户要求「那个『已连续套了多少格』的提示文本不要显示」，
 *     因此成功反馈只有一句不带格数的结果提示（语言文件里的计数键已删除）。</li>
 * </ul>
 */
public final class RsccSheathChain {
    /**
     * 分隔框架套壳成功的提示（<b>不带格数</b>）。
     *
     * <p><b>为什么没有「已连续套壳 N 格」</b>：用户明确要求「那个『已连续透壳多少格』的提示文本不要显示，
     * 不要提示已经连续套了多少个」，因此批量与单格共用这一句<b>结果型</b>反馈，
     * 不再有任何进度 / 计数文案（语言文件里对应的旧键也已删除）。</p>
     */
    private static final String MSG_SHEATHED = "message.rs_create_compat.frame_sheathed";
    /** 伪装框架裹壳成功的提示（与物品侧单格路径共用同一句，同样不带格数）。 */
    private static final String MSG_CAMOUFLAGED = "block.rs_create_compat.camouflage_frame.hint.wrapped";
    /** 分隔框架<b>取回</b>成功的提示（与单格路径共用同一句，极简结果、不带格数、不提是否归还）。 */
    private static final String MSG_UNSHEATHED = "message.rs_create_compat.frame_unsheathed";
    /** 伪装<b>取回</b>成功的提示（与物品侧 / 挂点共用同一句，同样极简）。 */
    private static final String MSG_UNWRAPPED = "block.rs_create_compat.camouflage_frame.hint.unwrapped";

    private RsccSheathChain() {
    }

    /**
     * 连锁套壳 / 连锁取回（服务端权威）：<b>本模组唯一的批量入口</b>，只由 FTB Ultimine 的连锁键路径调用。
     *
     * <h2>方向怎么定（本轮新增「整段取回」）</h2>
     * <p>同一个连锁键、同一条链路，靠<b>状态与手势</b>分流，而不是再加一个按键：</p>
     * <ul>
     *     <li><b>整段取回</b>：玩家<b>潜行</b>（潜行右键正是既有的「取下」手势），或者目标格对
     *     <b>手里这一版框架</b>来说已经生效（拿着分隔框架 = 这一格已套壳、拿着伪装框架 = 这一格已裹壳、
     *     空手 = 两份记录任一存在；见 {@link #framedFor}）—— 这时把整段的框架取下来；</li>
     *     <li><b>整段套壳</b>：其余情况（目标格还没套住、也没潜行）—— 与既有行为逐字一致。</li>
     * </ul>
     * <p>方向一旦定成「取回」，就<b>不会</b>再套壳，反之亦然：一次连锁只做一件事，
     * 绝不会出现「前半段套上、后半段取下」这种半截状态。</p>
     *
     * @param player              玩家（服务端）
     * @param hand                拿着框架（取回时也允许空手）的那只手 —— 物品消耗 / 归还作用在这一格上
     * @param anchor              FTB 本次连锁的目标格（{@code ShapeContext#origPos()}）
     * @param ultimineCandidates  FTB 本次连锁给出的候选坐标（已按它的形状与 {@code max_blocks} 限制过）
     * @param limit               本次允许处理的最大格数（{@code ShapeContext#maxBlocks()}，即 FTB 对这名玩家生效的上限）
     * @return 实际<b>新套上</b>或<b>取下</b>的格数；{@code 0} = 本模组不接手这次交互（调用方必须把右键交回原版，
     *     那样物品自己的 {@code useOn} 会执行「只作用命中那一格」/ 取下 / 给出提示，
     *     行为与「没装 FTB Ultimine」时完全一致）
     */
    public static int chainSheath(final ServerPlayer player, final InteractionHand hand, final BlockPos anchor,
                                  final Collection<BlockPos> ultimineCandidates, final int limit) {
        if (limit <= 0 || !player.mayBuild()) {
            return 0;
        }
        final ServerLevel level = player.serverLevel();
        final Family family = Family.of(level, anchor);
        if (family == null) {
            // 目标不是可套方块（管线以外的完整方块不参与连锁）：交回原版 → 物品的 useOn 只作用于命中那一格
            return 0;
        }
        final ItemStack stack = player.getItemInHand(hand);
        final FrameKind held = FrameKind.of(stack);
        if (player.isShiftKeyDown() || framedFor(level, anchor, held)) {
            // 整段取回：与单格「潜行右键取下」同一套手势 / 状态判据（见方法注释的「方向怎么定」）
            return chainTakeOff(player, stack, level, anchor, family, ultimineCandidates, limit);
        }
        if (held == null) {
            return 0; // 手里不是任一种框架：这次连锁与本模组无关（原版 / 其它模组的处理器照常接管）
        }
        final boolean infinite = held.infinite(stack);
        // 无限版不消耗；创造模式同样不消耗（与原版 ItemStack#consume 的 hasInfiniteMaterials 语义一致）
        final boolean free = infinite || player.hasInfiniteMaterials();
        final List<BlockPos> targets = plan(level, anchor, family, ultimineCandidates, limit);
        return apply(player, stack, level, held, affordable(level, held, targets, stack, free), infinite, free);
    }

    /**
     * 目标格对「手里这一版框架」来说是不是<b>已经生效</b>了（连锁方向的判据）。
     *
     * <p><b>为什么必须按手里那一版分别判定</b>：如果只看「这一格有没有任一种框架」，
     * 那么刚用分隔框架套住的线缆就永远没法再连锁裹伪装了（一连锁就被判成「取回」）。
     * 因此：手里是分隔框架 → 只看套壳记录；手里是伪装框架 → 只看伪装记录；
     * 空手则两份记录任一存在就判「取回」（与单格挂点的「外壳优先」取舍一致）。</p>
     */
    private static boolean framedFor(final Level level, final BlockPos pos, @Nullable final FrameKind held) {
        if (held == FrameKind.SHEATH) {
            return RsccSheaths.isSheathed(level, pos);
        }
        if (held == FrameKind.CAMOUFLAGE) {
            return RsccCamouflage.isCamouflaged(level, pos);
        }
        return RsccSheaths.isSheathed(level, pos) || RsccCamouflage.isCamouflaged(level, pos);
    }

    /**
     * 连锁取回：把整段的框架（外壳 / 套壳）一次取下，并<b>按格归还当初真的扣过的那一个</b>。
     *
     * <h2>取哪一层</h2>
     * <p>沿用单格路径的取舍（{@code RsccCamouflageInteraction} 里伪装优先于分隔框架，因为它是外面那一层）：</p>
     * <ul>
     *     <li>手里是<b>伪装框架</b> → 只取伪装；</li>
     *     <li>手里是<b>任一种分隔框架</b> → 只取套壳；</li>
     *     <li><b>空手</b> → 逐格「有伪装就先取伪装，否则取套壳」。</li>
     * </ul>
     *
     * <h2>守恒（用户原话：「无限的话就全部取消掉，普通框架需要返还」）</h2>
     * <p>归还判定<b>完全落在每一格自己的记录上</b>（{@link RsccSheaths#removeAll} /
     * {@link RsccCamouflage#removeAll} 复用单格那套 {@code paid} / {@code frameConsumed} 位），
     * 与「手里拿的是哪一版框架」无关：普通版当初扣过就还，无限版 / 创造模式当初没扣就一个都不产出 ——
     * 于是「取回多少格就归还多少个普通框架」永远成立，也绝不可能靠来回套 / 取刷出物品。</p>
     *
     * <p><b>批量上限</b>沿用 {@code plan(...)} 的 {@code limit}（= FTB 的 {@code max_blocks}），
     * 本模组不另立任何数值。</p>
     *
     * @return 真正被取下的格数；{@code 0} = 一格都没取到（调用方把右键交回原版），
     *     或手持的是扳手一类<b>不该由本模组接管</b>的工具（见 {@link #isTakeOffTool}）
     */
    private static int chainTakeOff(final ServerPlayer player, final ItemStack stack, final ServerLevel level,
                                    final BlockPos anchor, final Family family,
                                    final Collection<BlockPos> ultimineCandidates, final int limit) {
        if (!isTakeOffTool(stack)) {
            return 0; // 扳手 / 其它工具：这一档整体让给原版（RS / Create 的拆除照旧）
        }
        final FrameKind held = FrameKind.of(stack);
        final List<BlockPos> targets = plan(level, anchor, family, ultimineCandidates, limit);
        final List<BlockPos> camouflage = new ArrayList<>();
        final List<BlockPos> sheaths = new ArrayList<>();
        for (final BlockPos pos : targets) {
            final boolean camouflaged = RsccCamouflage.isCamouflaged(level, pos);
            final boolean sheathed = RsccSheaths.isSheathed(level, pos);
            if (held == FrameKind.CAMOUFLAGE) {
                if (camouflaged) {
                    camouflage.add(pos);
                }
            } else if (held == FrameKind.SHEATH) {
                if (sheathed) {
                    sheaths.add(pos);
                }
            } else if (camouflaged) {
                camouflage.add(pos); // 空手：外壳优先（它是外面那一层，与单格挂点的取舍一致）
            } else if (sheathed) {
                sheaths.add(pos);
            }
        }
        final int unwrapped = camouflage.isEmpty() ? 0 : RsccCamouflage.removeAll(level, player, camouflage);
        final int unsheathed = sheaths.isEmpty() ? 0 : RsccSheaths.removeAll(level, player, sheaths);
        final int removed = unwrapped + unsheathed;
        if (removed <= 0) {
            // 一格都没取到（例如手持框架但整段本来就没套）：交回原版，让单格路径照常给提示 / 取下
            return 0;
        }
        // 一次极简结果反馈：不带格数，也不解释创造模式 / 是否消耗 / 是否归还（用户明确要求删掉这些废话）
        player.displayClientMessage(Component.translatable(unsheathed > 0 ? MSG_UNSHEATHED : MSG_UNWRAPPED), true);
        return removed;
    }

    /**
     * 连锁取回允许手持的物品：空手 / 任一种分隔框架 / 伪装框架。
     *
     * <p><b>为什么刻意不含扳手</b>：与单格路径 {@code RsccSheathInteraction#isTakeOffTool} 同一条理由 ——
     * 扳手是 RS / Create 自己的「拆除 / 拾取」工具，潜行 + 扳手必须完整让给它们
     * （{@code AbstractBaseBlock#tryUseWrench} 会直接拆掉整根线缆并按它们的规则掉落），
     * 否则「用扳手拆掉这根线缆」这条最自然的操作会被本模组吞掉。手持扳手时连锁取回因此直接不接手
     * （返回 0），这次右键照旧由它们结算。</p>
     */
    private static boolean isTakeOffTool(final ItemStack stack) {
        return stack.isEmpty()
            || stack.getItem() instanceof SeparationFrameItem
            || stack.getItem() instanceof CamouflageFrameItem;
    }

    /**
     * 从候选里挑出「还没套住 / 还没裹住、且这次付得起」的那些（顺序即候选顺序：目标格与近处优先）。
     *
     * @param free 无限版 / 创造模式：不消耗 → 数量不设限
     */
    private static List<BlockPos> affordable(final Level level, final FrameKind kind,
                                             final List<BlockPos> candidates,
                                             final ItemStack stack, final boolean free) {
        final List<BlockPos> toSheath = new ArrayList<>(candidates.size());
        for (final BlockPos pos : candidates) {
            if (kind.alreadyWrapped(level, pos)) {
                continue; // 已经有壳：跳过（幂等，不重复消耗）
            }
            if (!free && toSheath.size() >= stack.getCount()) {
                break; // 普通版数量不足：只套得起的那些（先目标格、再 FTB 顺序、再 BFS 近处优先）
            }
            toSheath.add(pos);
        }
        return toSheath;
    }

    /**
     * 批量落库 + 按格扣物品 + 一句成功反馈（两种框架共用这一段，因此消耗规则只有一份）。
     *
     * <p><b>顺序</b>：先写记录、再扣物品（记录写不进去就绝不扣），且只扣 {@code addAll} 返回的
     * 「真正新增」格数 —— 因此「物品数 == 套壳格数」恒成立，任何模式下一进一出都严格相抵。</p>
     *
     * @return 实际新套上的格数；{@code 0} = 这一批什么都不用做（调用方据此把右键交回原版）
     */
    private static int apply(final Player player, final ItemStack stack, final ServerLevel level,
                             final FrameKind kind, final List<BlockPos> toSheath,
                             final boolean infinite, final boolean free) {
        if (toSheath.isEmpty()) {
            // 全部都已套住 / 一个也套不起：交回原版（它会给出「已套住可潜行取下」的提示或直接取下），
            // 这样「连锁」与既有的单格语义不会互相打架。
            return 0;
        }
        final int added = kind.addAll(level, toSheath, infinite, !free);
        if (added <= 0) {
            return 0;
        }
        // 先写记录、再扣物品（与单格路径同一条守恒规则）：记录写不进去就绝不扣。
        // 只扣「真正新增」的格数，因此「物品数 == 套壳格数」恒成立。
        if (!free) {
            stack.consume(added, player);
        }
        player.displayClientMessage(Component.translatable(kind.messageKey()), true);
        return added;
    }

    /**
     * 组装本次要处理的坐标（目标格 → FTB 候选 → 同族邻接 BFS），并按 {@code limit} 截断。
     * <p>用 {@link LinkedHashSet} 保序去重：顺序决定「数量不足时先套哪几格」，
     * 因此把「玩家真正点的那一格」放在第 0 位。</p>
     */
    private static List<BlockPos> plan(final Level level, final BlockPos anchor, final Family family,
                                       final Collection<BlockPos> ultimineCandidates, final int limit) {
        final Set<BlockPos> ordered = new LinkedHashSet<>();
        ordered.add(anchor);
        for (final BlockPos pos : ultimineCandidates) {
            if (ordered.size() >= limit) {
                break;
            }
            if (pos == null || pos.equals(anchor) || !level.isLoaded(pos)) {
                continue;
            }
            if (!family.matches(level, pos)) {
                continue; // 不同族（例如线缆旁边恰好贴着流体管道）：不一起套
            }
            ordered.add(pos);
        }
        if (ordered.size() < limit) {
            expand(level, anchor, family, ordered, limit);
        }
        return List.copyOf(ordered);
    }

    /**
     * 从 {@code anchor} 出发按六邻接逐层展开<b>同族</b>方块，把新坐标补进 {@code ordered}（到 {@code limit} 为止）。
     * <p>已访问集合先于族别判定登记，保证每格最多被检查一次；坐标未加载直接跳过（绝不为了一格判定去加载区块）。</p>
     */
    private static void expand(final Level level, final BlockPos anchor, final Family family,
                               final Set<BlockPos> ordered, final int limit) {
        final Set<BlockPos> visited = new HashSet<>();
        final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(anchor);
        queue.add(anchor);
        while (!queue.isEmpty() && ordered.size() < limit) {
            final BlockPos cell = queue.poll();
            for (final Direction direction : Direction.values()) {
                final BlockPos next = cell.relative(direction);
                if (!visited.add(next) || !level.isLoaded(next)) {
                    continue;
                }
                if (!family.matches(level, next)) {
                    continue;
                }
                ordered.add(next);
                queue.add(next);
                if (ordered.size() >= limit) {
                    return;
                }
            }
        }
    }

    /**
     * 两种框架在「连锁」上的唯一差异：状态写进哪份记录、成功反馈用哪句话。
     *
     * <p><b>为什么用枚举而不是两个布尔参数</b>：「写哪份记录」「查哪份记录」「成功说哪句话」
     * 这三件事必须<b>同进同退</b>（把 A 的记录写进去却查 B 的幂等位，就会出现
     * 「套了没收钱」或「收了钱没套」）。把它们绑在同一个常量上，就不可能出现这种错配；
     * 而规划与消耗仍只有一份实现，两个框架的规则不会漂移。</p>
     */
    private enum FrameKind {
        /** 分隔框架（含无限版）：状态在 {@link RsccSheaths}，取下 / 被拆时按 {@code paid} 位归还。 */
        SHEATH(MSG_SHEATHED) {
            @Override
            boolean alreadyWrapped(final Level level, final BlockPos pos) {
                return RsccSheaths.isSheathed(level, pos);
            }

            @Override
            int addAll(final ServerLevel level, final List<BlockPos> toSheath, final boolean infinite,
                       final boolean paid) {
                return RsccSheaths.addAll(level, toSheath, infinite, paid);
            }

            @Override
            boolean infinite(final ItemStack stack) {
                return stack.getItem() instanceof final SeparationFrameItem frame && frame.isInfiniteVariant();
            }
        },
        /** 伪装框架（无无限版）：状态在 {@link RsccCamouflage}，外壳材质由之后手持方块右键再选。 */
        CAMOUFLAGE(MSG_CAMOUFLAGED) {
            @Override
            boolean alreadyWrapped(final Level level, final BlockPos pos) {
                return RsccCamouflage.isCamouflaged(level, pos);
            }

            @Override
            int addAll(final ServerLevel level, final List<BlockPos> toSheath, final boolean infinite,
                       final boolean paid) {
                // 伪装没有无限版，infinite 恒为 false；这里只转交「这次是否真的会扣框架」那一位
                return RsccCamouflage.addAll(level, toSheath, paid);
            }
        };

        private final String messageKey;

        FrameKind(final String messageKey) {
            this.messageKey = messageKey;
        }

        /** 该物品属于哪一种框架；都不是（空手 / 别的物品）返回 {@code null}。 */
        @Nullable
        static FrameKind of(final ItemStack stack) {
            if (stack.getItem() instanceof SeparationFrameItem) {
                return SHEATH;
            }
            return stack.getItem() instanceof CamouflageFrameItem ? CAMOUFLAGE : null;
        }

        /** 成功反馈的语言键（不带参数，绝不出现格数）。 */
        String messageKey() {
            return this.messageKey;
        }

        /** 这一格是否已经处于本框架的「已生效」状态（幂等判据；两份记录各查各的）。 */
        abstract boolean alreadyWrapped(Level level, BlockPos pos);

        /** 把这一批写进本框架的记录，返回真正<b>新增</b>的格数（幂等与守恒由被调用方保证）。 */
        abstract int addAll(ServerLevel level, List<BlockPos> toSheath, boolean infinite, boolean paid);

        /** 这一件手持物品是不是「无限版」（只有分隔框架有无限版）。 */
        boolean infinite(final ItemStack stack) {
            return false;
        }
    }

    /**
     * 「同类」的族别（两种框架的连锁共用同一张族别表）：RS 线缆类（线缆 / 输入总线 / 输出总线）
     * 与 Create 流体管道各自成族。
     *
     * <p>「可套」判定复用单格路径的唯一入口
     * {@link SeparationFrameGuard#isSheathable(net.minecraft.world.level.BlockGetter, BlockPos, BlockState)}
     * —— 这样「哪些方块能套」永远只有一份口径，不会出现「单格能套、连锁套不上」的漂移。</p>
     *
     * <p><b>为什么「完整方块」不在这里成族</b>：FTB 的连锁形状是按<b>方块状态等价</b>匹配的，
     * 而「一排完整方块」既可能是同一面墙、也可能是不同方块拼的；把它交给 FTB 的方块状态匹配
     * 既不可控、也与「同族才一起套」这条口径打架。因此完整方块不参与连锁
     * （{@link #chainSheath} 见到非管线目标就直接返回 0，右键交回原版，只套命中那一格）。</p>
     */
    private enum Family {
        /** RS 线缆类（线缆 / 输入总线 / 输出总线）。 */
        WIRE,
        /** Create 流体管道（{@code FluidPipeBlock} 及其子类）。 */
        FLUID_PIPE;

        /** 该坐标的族别；不是「管线类可套壳方块」时返回 {@code null}。 */
        @Nullable
        static Family of(final Level level, final BlockPos pos) {
            if (level == null || pos == null || !level.isLoaded(pos)) {
                return null;
            }
            final BlockState state = level.getBlockState(pos);
            if (!SeparationFrameGuard.isSheathable(level, pos, state)) {
                return null;
            }
            if (RsccWireBlocks.isWire(state)) {
                return WIRE;
            }
            return SeparationFrameGuard.isFluidPipe(state) ? FLUID_PIPE : null;
        }

        /** 该坐标是否属于本族（判定口径同 {@link #of}）。 */
        boolean matches(final Level level, final BlockPos pos) {
            return of(level, pos) == this;
        }
    }
}
