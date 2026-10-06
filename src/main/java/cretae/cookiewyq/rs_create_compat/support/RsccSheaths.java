package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.advancement.RsccAdvancements;
import cretae.cookiewyq.rs_create_compat.network.SyncSheathPositionsPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「分隔框架套壳」的唯一状态中心（服务端权威 + 客户端只读镜像）。
 *
 * <h2>为什么状态是「坐标标记」而不是「多一个方块」</h2>
 * <p>用户要的是「真正的把它套在里面 —— 在一个线缆方块的基础上、在里面再给它加一层」，
 * 而一个 Minecraft 方块格里<b>只能有一个方块</b>。想在那一格「套壳」，只有两条路：</p>
 * <ol>
 *     <li><b>替换那一格</b>（Create 自己的 {@code EncasedPipeBlock} 就是这条路：把 {@code create:fluid_pipe}
 *     换成 {@code create:encased_fluid_pipe}，用同一个 {@code FluidPipeBlockEntity} 类，
 *     再用 {@code FluidTransportBehaviour#cacheFlows/loadFlows} 把管里的流体搬过去）——
 *     对 Create 流体管道可行，但本模组还要覆盖 <b>RS 线缆 / 输入总线 / 输出总线</b>，
 *     而 RS 的网络节点容器是由方块实体类 + 方块类共同构造的，替换方块等于重建网络节点，
 *     无法安全委托（详见交付报告的代价评估）；</li>
 *     <li><b>不替换、只标记</b>（本类）：那一格<b>仍然是原来那根管道 / 那段线缆</b>，
 *     所以它的能力、网络节点、流体内容<b>一个字节都没变</b>（不需要任何委托，也不可能丢内容）；
 *     我们只在服务端记下「这一格被套住了」+「套上那一刻它连到哪几侧」，并据此做两件事：
 *     <b>冻结它与邻居的连接</b>、<b>在它外面画一层外套模型</b>。</li>
 * </ol>
 * <p>选第 2 条：它在「用户可见行为」上与「真套壳」等价，却把「替换方块 + 能力委托」这个
 * 风险最高、最容易丢内容的环节整个绕开了。</p>
 *
 * <h2>套壳语义 = 「冻结当前连接状态」，<b>不是</b>「断开」（本轮纠正）</h2>
 * <p>用户原话：「那个框架不是说让你不会连接到别的地方，他是说<b>保存当前连接状态不会发生改变</b>。
 * 就比如说我现在有一条直的，然后我给它全部套上；套上完成之后我在旁边再放别的线缆，
 * 此时这一条直的线缆就不会跟旁边这条新的线缆进行连接。它是<b>保存固定被套上之前最后一刻的状态</b>，
 * 而不是让它直接断开。」</p>
 * <p>因此每格套壳记录里除了「是否无限版」还多存一份<b>连接快照</b>：套上那一刻，
 * 这一格<b>朝每一条侧（上下左右前后）是否处于连通状态</b>（6 位掩码，位 = {@link Direction#ordinal()}）。
 * 之后任何一侧要连过来，都以快照为准 ——
 * 快照里有这一侧就照旧连（<b>套上前就存在的连接全部保留</b>），没有就不连（<b>新邻居一律不连</b>）。
 * 判定细节与对称性论证见 {@link SeparationFrameGuard}。</p>
 *
 * <h2>与「旁边放一个框架方块」的区别</h2>
 * <p>判据完全落在「那一格自己被套住」上：把框架方块放在管道旁边<b>不影响任何连接</b>。
 * 这一条是上一轮纠正的，本轮不变。</p>
 *
 * <h2>持久化与守恒（硬要求：绝不复制 / 销毁方块与物品流体）</h2>
 * <ul>
 *     <li>记录存在服务端的 {@link SavedData} 里（数据文件 {@value #DATA_NAME}，
 *     由 {@code DimensionDataStorage} 落到 {@code world/data/}），读档后仍然成立；</li>
 *     <li>套上 / 取下只改这一份记录：<b>不放置方块、不销毁方块、不碰方块实体</b>，
 *     因此管道里的流体、线缆上的网络状态原封不动；</li>
 *     <li>物品收支：套上消耗 1 个（普通版；无限版不消耗），取下归还 1 个（普通版；无限版不归还），
 *     创造模式不消耗也不产出 —— 数量严格守恒，见 {@code item/SeparationFrameItem}；</li>
 *     <li>那一格方块被拆掉 / 被替换 / 被炸掉时，由 {@link #onBlockChanged} 立刻清掉记录
 *     （含快照），不留「幽灵套壳」，也不会在重新放回管道后莫名其妙保持断开。</li>
 * </ul>
 *
 * <h2>2026-09-25 补：普通（非无限）分隔框架的<b>回收方式</b>（用户要求「这一轮必须给我做」）</h2>
 * <p>用户原话：「分隔框架到底该怎么回收？现在你没有给我任何回收方式」。修法是把「回收」做成
 * <b>三条彼此独立、都真的能拿回物品</b>的路径，并让它们共用同一份守恒判据：</p>
 * <ol>
 *     <li><b>潜行右键</b>（空手 / 分隔框架 / 扳手）→ 取下外壳并归还框架本体
 *     （{@code support/RsccSheathInteraction} 的全局挂点 + {@code item/SeparationFrameItem#useOn}，
 *     原来那条物品路径保留）；</li>
 *     <li><b>被套的那一格方块被拆掉 / 被替换 / 被炸掉</b> → 框架本体<b>原样掉回世界</b>
 *     （见 {@link #onBlockChanged}）。这一条是本轮新增的：此前只删记录、不掉物品，
 *     等于把玩家已经花掉的框架<b>销毁</b>掉了；</li>
 *     <li><b>拿走那一格本身</b>（潜行 + 扳手让 RS 拆下整根线缆）→ 与第 2 条同一条清理链路。</li>
 * </ol>
 * <p><b>为什么要多存一位「当初真的扣过物品吗」（{@code paid}）</b>：归还的判据绝不能是
 * 「当前玩家的游戏模式」——创造模式下套上（没扣）的记录如果被生存玩家取下，就凭空产出一个框架。
 * 因此与伪装框架的 {@code Camo#frameConsumed} 完全同一套做法：把「这一格当初有没有真的付出 1 个物品」
 * 记进存档，取下 / 被拆时<b>只归还当初真的付过的</b>，任何组合下一进一出都严格相抵。
 * 旧存档没有这一位，按旧规则（普通版一律扣过、无限版不扣）补齐，行为与改动前逐字一致。</p>
 *
 * <h2>旧存档兼容（没有快照的老记录）</h2>
 * <p>旧记录只带一个「是否无限版」的布尔值，没有快照。本类在<b>服务端第一次取用这份存档数据时</b>
 * （{@code migrateIfNeeded}）用<b>与套壳完全同一套判定</b>（{@link SeparationFrameGuard#snapshotMask}）
 * 给它们补一次快照，落盘后与新建的记录再无区别。降级策略明确写在那里：
 * 补快照时<b>不把别的「尚未补齐」的老记录当成阻断源</b>，即老套壳格按「补快照那一刻的自然连通情况」
 * 定格，而不会因为它们之间曾经互不连接就把对方永久锁死 ——
 * 旧的「套上即断开一切」规则已经不存在，没有理由让老存档继续停在那个状态。</p>
 *
 * <h2>双端一致性</h2>
 * <p>RS 的连接臂外形与 Create 的管口开放面在<b>客户端也会重算</b>，所以客户端必须知道哪些格子被套住、
 * 快照是什么，否则重算会把连接「长回来」。客户端没有 {@code SavedData}，改由
 * {@code SyncSheathPositionsPacket}（S2C 整份快照）维护一份只读镜像；服务端每次改动<b>先发包、再发方块更新</b>，
 * 客户端重算时用的已是新数据（与 {@link RsccCableCuts} 完全同一套做法）。</p>
 */
public final class RsccSheaths {
    /** 存档数据文件名（{@code world/data/rscc_sheaths.dat}）。 */
    public static final String DATA_NAME = "rscc_sheaths";

    /** 旧的「是否无限版」段（保持原格式，旧存档读回来仍然认识）。 */
    private static final String KEY_SHEATHS = "sheaths";
    /** 新增的连接快照段（键 = 坐标，值 = 6 位掩码 byte；<b>键是否存在</b>用来区分「有快照 / 老记录」）。 */
    private static final String KEY_MASKS = "masks";
    /** 新增的「当初真的扣过 1 个框架物品」段（键 = 坐标，值 = 布尔）。缺键 = 本模组较早版本的老记录。 */
    private static final String KEY_PAID = "paid";

    /** 「没有快照」标记：只出现在刚读进内存、还没补齐快照的老记录上。 */
    static final int NO_SNAPSHOT = -1;

    /** 空的客户端镜像（未收到快照 / 快照为空时共用，避免每次新建对象）。 */
    private static final Map<BlockPos, Sheath> EMPTY = Map.of();

    /** 客户端镜像：坐标 → 记录（整份替换）。未收到快照时为 {@code null} → 一律按「没套」处理。 */
    private static volatile Map<BlockPos, Sheath> clientMirror;

    private RsccSheaths() {
    }

    /**
     * 一条套壳记录。
     *
     * @param infinite 是否无限版（叠加附魔光泽 + 取下时不归还物品）
     * @param mask     套上那一刻的连接快照：位 = {@link Direction#ordinal()}，置位 = 套上时这一侧是连通的；
     *                 {@link #NO_SNAPSHOT} = 老存档记录，尚未补齐（补齐后会落盘）
     */
    private record Sheath(boolean infinite, int mask) {
    }

    /**
     * 一次「取下套壳」的结果：调用方据此把物品还给玩家（与伪装框架的 {@code Camo} 同一套守恒口径）。
     *
     * @param infinite 取下的那一格是不是无限版（无限版取下不归还）
     * @param paid     当初套上时<b>真的</b>从玩家手里扣掉了 1 个框架物品（创造模式 / 连锁的免费路径为 false）
     */
    public record SheathRemoval(boolean infinite, boolean paid) {
        /** 该不该把 1 个框架本体还给玩家：只有「普通版且当初真的付过」才归还（否则就是凭空产出）。 */
        public boolean refund() {
            return paid && !infinite;
        }
    }

    /**
     * 取下套壳后把框架本体还给玩家（背包放不下就掉在脚下，<b>绝不凭空消失</b>）。
     *
     * <p><b>为什么集中在这里</b>：取下的入口有两个（物品侧 {@code item/SeparationFrameItem#useOn}
     * 与新的全局挂点 {@code support/RsccSheathInteraction}）。物品收支是硬约束（不复制、不销毁），
     * 两处各写一遍迟早漂移，因此归还判定（{@link SheathRemoval#refund()} 与
     * {@code Player#hasInfiniteMaterials}）与「背包满则掉落」只保留这一份实现。</p>
     *
     * <p><b>为什么返回 boolean</b>：用户实机反馈「你说他会退还，但我并没有看见他退还」。
     * 创造模式 / 无限版套上的那一位本来就没有消耗物品（守恒位 {@code paid = false}），
     * 取下时因此<b>一个都不还</b> —— 这是对的，但必须让调用方知道「这次到底还了没有」，
     * 才好给玩家一句明确的原因（否则玩家只会以为退还坏了，见 {@code RsccSheathInteraction#takeOff}）。</p>
     *
     * @return {@code true} = 真的把 1 个框架本体还给了玩家（背包或脚下）；{@code false} = 本来就没有可还的
     */
    public static boolean refund(final Player player, @Nullable final SheathRemoval removal) {
        if (removal == null || !removal.refund()) {
            return false;
        }
        giveOne(player);
        return true;
    }

    /**
     * 一次把 {@code count} 个框架本体还给玩家（<b>连锁取回</b>用；背包放不下就掉在脚下，绝不凭空消失）。
     *
     * <p><b>为什么与单格共用 {@link #giveOne}</b>：连锁取回会一次取下整段（最多 FTB 的
     * {@code max_blocks} 格），「还到背包、放不下就掉脚下」这件事若各写一份，迟早有一边漏掉某个分支；
     * 判据（{@link SheathRemoval#refund()}）与落地动作都只有一份实现，因此批量路径不可能不守恒。</p>
     */
    public static void refund(final Player player, final int count) {
        for (int i = 0; i < count; i++) {
            giveOne(player);
        }
    }

    /** 把 1 个框架本体还给玩家：背包放不下就掉在脚下（绝不销毁、绝不凭空消失）。 */
    private static void giveOne(final Player player) {
        final ItemStack refund = new ItemStack(RS_Create_Compat.SEPARATION_FRAME_ITEM.get());
        if (!player.getInventory().add(refund)) {
            player.drop(refund, false);
        }
    }

    // ------------------------------------------------------------------
    // 查询（连接阻断判定 + 客户端渲染共用的唯一入口）
    // ------------------------------------------------------------------

    /**
     * 该坐标上的管道 / 线缆是否被分隔框架套住。
     *
     * <p><b>调用点</b>：{@link SeparationFrameGuard#blocksConnection}（RS 网络图连边 + 连接臂外形 +
     * 本模组线缆搜索）与 {@link SeparationFrameGuard#blocksFluidPipeConnection}（Create 管口开放面 +
     * 双侧网络遍历 + 外形），以及客户端外套渲染器。</p>
     */
    public static boolean isSheathed(final Level level, final BlockPos pos) {
        if (pos == null) {
            return false;
        }
        if (level instanceof final ServerLevel serverLevel) {
            return saved(serverLevel).sheaths.containsKey(pos);
        }
        final Map<BlockPos, Sheath> mirror = clientMirror;
        return mirror != null && mirror.containsKey(pos);
    }

    /**
     * 该坐标被套住时的<b>连接快照掩码</b>（位 = {@link Direction#ordinal()}，置位 = 套上时这一侧连通）。
     *
     * <p>返回 {@code null} 表示「这一侧的判定不该由快照说话」，两种情况：
     * ①这一格没被套住；②这一格是老存档记录、快照还没补齐（此时按自然规则放行，
     * 既不会凭空断开，也不会凭空连上 —— 补齐后立刻按快照执行）。</p>
     */
    @Nullable
    public static Integer sheathMask(final Level level, final BlockPos pos) {
        if (pos == null) {
            return null;
        }
        final Sheath sheath;
        if (level instanceof final ServerLevel serverLevel) {
            sheath = saved(serverLevel).sheaths.get(pos);
        } else {
            final Map<BlockPos, Sheath> mirror = clientMirror;
            sheath = mirror == null ? null : mirror.get(pos);
        }
        if (sheath == null || sheath.mask() < 0) {
            return null;
        }
        return sheath.mask();
    }

    /** 该坐标被套住的是不是「无限版」（客户端镜像 / 服务端记录都只读，用于叠加附魔光泽与归还物品）。 */
    public static boolean isInfiniteSheath(final Level level, final BlockPos pos) {
        if (pos == null) {
            return false;
        }
        if (level instanceof final ServerLevel serverLevel) {
            final Sheath sheath = saved(serverLevel).sheaths.get(pos);
            return sheath != null && sheath.infinite();
        }
        final Map<BlockPos, Sheath> mirror = clientMirror;
        final Sheath sheath = mirror == null ? null : mirror.get(pos);
        return sheath != null && sheath.infinite();
    }

    /**
     * 统计给定包围盒内<b>有多少格被分隔框架套住</b>（只读；蓝图导出提示用）。
     *
     * <h2>为什么需要它（缺口 1：套壳记录不进蓝图）</h2>
     * <p>套壳只存在本模组的 {@link SavedData} 里，<b>不占方块</b>，而 Create 的蓝图只写
     * {@code StructureTemplate}（方块 + 方块实体）—— 因此导出 / 粘贴后套壳必然全丢，
     * 玩家却看不到任何提示（用户原话：「我明明放了框架，它却说不行」）。判定的原始语义不动，
     * 这里只提供「这一次导出会丢掉多少处」这个<b>事实</b>，由导出路径当面告诉玩家要自己补回来。</p>
     *
     * <h2>为什么两头都能算、且算的是同一份真值</h2>
     * <p>服务端读权威存档数据（{@link Saved}），客户端读 {@code SyncSheathPositionsPacket} 维护的
     * <b>整份只读镜像</b>；镜像本来就是「服务端每次改动后整份重发」的，因此两边算出来的台数一致，
     * 不需要为了提示去问服务端（导出动作本身就发生在客户端手上）。</p>
     *
     * @param box 蓝图选择区（{@code BoundingBox.fromCorners(first, second)}），{@code null} = 不统计
     * @return 套壳格数；没有记录 / 没有镜像时返回 0
     */
    public static int countIn(final Level level, @Nullable final BoundingBox box) {
        if (level == null || box == null) {
            return 0;
        }
        final Map<BlockPos, Sheath> map;
        if (level instanceof final ServerLevel serverLevel) {
            map = saved(serverLevel).sheaths;
        } else {
            map = clientMirror;
        }
        if (map == null || map.isEmpty()) {
            return 0; // 常见情况：这个世界从来没套过壳 —— 一次 isEmpty 即返回
        }
        int count = 0;
        for (final BlockPos pos : map.keySet()) {
            if (box.isInside(pos)) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // 改动（仅服务端调用）
    // ------------------------------------------------------------------

    /**
     * 给这一格套上外壳（<b>先按「套上前的世界状态」算好连接快照，再写记录</b>）。
     *
     * <p><b>顺序为什么不能反</b>：快照的判定本身会读套壳记录（邻居若已被套住，由邻居的快照说话）。
     * 如果先写记录再算快照，这一格自己的新记录就会参与自己的判定 —— 那是自污染。
     * 单格路径先算后写天然没这个问题，批量路径见 {@link #addAll}。</p>
     *
     * @param paid 调用方这一次是否<b>真的</b>从玩家手里扣掉了 1 个框架物品（普通模式为 true、
     *             无限版 / 创造模式为 false）；取下与被拆时按它决定要不要归还，任何模式下一进一出都相抵
     * @return {@code false} = 这一格本来就套着（调用方据此给出「先取下」的提示，并且<b>不</b>扣物品）
     */
    public static boolean add(final ServerLevel level, final BlockPos pos, final boolean infinite, final boolean paid) {
        final Saved saved = saved(level);
        if (saved.sheaths.containsKey(pos)) {
            return false;
        }
        saved.sheaths.put(pos, new Sheath(infinite, SeparationFrameGuard.snapshotMask(level, pos)));
        if (paid) {
            saved.paid.add(pos); // 只有真的扣过物品才记：取下 / 被拆时按它决定要不要归还（守恒）
        }
        saved.setDirty();
        // 顺序要点：先把新快照发给客户端，再发方块更新 —— 客户端重算连接臂时用的已是新数据
        broadcast(level, saved);
        SeparationFrameGuard.refreshAround(level, pos);
        // 成就触发点：套上框架外壳（服务端；无限版会再多发一次它的专属事件）
        RsccAdvancements.onSheathApplied(level, pos, infinite);
        return true;
    }

    /**
     * 一次给<b>一批</b>坐标套上外壳（连锁套壳的唯一批量入口）。
     *
     * <p><b>为什么分两趟（关键正确性要点）</b>：连锁是批量写记录，如果「写一格 → 算下一格的快照」
     * 交替进行，前面几格的写入就会玷污后面几格的快照计算（后写的格子会看到「前面的格子已经套住」，
     * 判定于是走快照而不是自然状态）。因此本方法<b>第一趟只算快照、一条记录都不写</b>
     * （此时整批格子看到的都是同一份「套上前」的世界状态），<b>第二趟才统一写记录</b> ——
     * 于是「整段一起套上」与「逐格分别套上」得到完全一样的快照，且结果与传入顺序无关。</p>
     *
     * <p><b>为什么不是循环调用 {@link #add}</b>：{@code add} 每次都要「整份快照广播 + 刷新本格与六邻」，
     * 连锁套 64 格就会发 64 个 S2C 整份快照，并把许多格子重复刷新上百次。本方法把这一批当成<b>一次事务</b>：
     * 先全部写进记录 → 只广播一次整份快照 → 只刷新去重后的坐标集合。</p>
     *
     * <p><b>幂等</b>：已经套住的坐标直接跳过（既不重复消耗物品，也不重复触发刷新）。</p>
     *
     * @param paid 调用方这一次是否<b>真的</b>会从玩家手里按格扣掉物品（无限版 / 创造模式为 false）；
     *             与 {@link #add} 同一位语义，只影响「取下 / 被拆时还不还」
     * @return 真正<b>新增</b>的套壳格数（调用方据此逐个消耗物品，保证「物品数 == 套壳数」）
     */
    public static int addAll(final ServerLevel level, final Collection<BlockPos> positions, final boolean infinite,
                             final boolean paid) {
        if (positions.isEmpty()) {
            return 0;
        }
        final Saved saved = saved(level);
        // 第一趟：只算快照。此刻这一批一条记录都还没写 → 每格看到的都是「套上前」的同一份世界状态，
        // 因此不存在「前几格的写入污染后面格的快照计算」。LinkedHashMap 顺带完成去重与保序。
        final Map<BlockPos, Integer> masks = new LinkedHashMap<>();
        for (final BlockPos pos : positions) {
            if (pos == null || saved.sheaths.containsKey(pos)) {
                continue; // 已经套住：跳过（幂等）
            }
            masks.put(pos, SeparationFrameGuard.snapshotMask(level, pos));
        }
        if (masks.isEmpty()) {
            return 0;
        }
        // 第二趟：统一写记录，并收集去重的刷新集合（每个被套格 + 它的六个邻格，最后各刷一次）
        final Set<BlockPos> refreshTargets = new LinkedHashSet<>();
        for (final Map.Entry<BlockPos, Integer> entry : masks.entrySet()) {
            saved.sheaths.put(entry.getKey(), new Sheath(infinite, entry.getValue()));
            if (paid) {
                saved.paid.add(entry.getKey()); // 整批同一位：连锁与单格走的是同一条守恒规则
            }
            refreshTargets.add(entry.getKey());
            for (final Direction direction : Direction.values()) {
                refreshTargets.add(entry.getKey().relative(direction));
            }
        }
        saved.setDirty();
        // 顺序要点同 add：先把新快照发给客户端，再发方块更新 —— 客户端重算连接臂时用的已是新数据
        broadcast(level, saved);
        SeparationFrameGuard.refreshBlocks(level, refreshTargets);
        // 成就触发点：批量套壳真正新增了多格才算「一整段」（单格右键走 add，不会误触发）
        if (masks.size() >= 2) {
            RsccAdvancements.onSheathChained(level, masks.keySet().iterator().next());
        }
        return masks.size();
    }

    /**
     * 把这一格外壳取下（连同快照一起删除）。
     *
     * <p><b>取下 = 恢复自然连接</b>：没有记录就没有快照，判定自然回到「未套壳」那一支，
     * 因此不需要主动重建任何连接 —— 被动的自然判定即可。刷新本格与六邻是为了让这一步<b>立刻</b>可见。</p>
     *
     * @return {@code null} = 这一格本来就没套；否则返回 {@link SheathRemoval}
     *     （调用方据此决定要不要把物品还给玩家：无限版 / 当初没扣过都不归还）
     */
    @Nullable
    public static SheathRemoval remove(final ServerLevel level, final BlockPos pos) {
        final Saved saved = saved(level);
        final Sheath removed = saved.sheaths.remove(pos);
        if (removed == null) {
            return null;
        }
        final boolean paid = saved.paid.remove(pos);
        saved.setDirty();
        broadcast(level, saved);
        SeparationFrameGuard.refreshAround(level, pos);
        return new SheathRemoval(removed.infinite(), paid);
    }

    /**
     * 一次取回<b>整段</b>套壳（连锁取回的唯一批量入口，与 {@link #addAll} 对称）。
     *
     * <h2>为什么与 {@link #addAll} 对称、而不是循环调用 {@link #remove}</h2>
     * <p>{@code remove} 每格都要「整份快照广播 + 刷新本格与六邻」，连锁取 64 格就会发 64 个 S2C 整份快照，
     * 并把许多格子重复刷新上百次。这里把整批当成<b>一次事务</b>：统一删记录 → 只广播一次 →
     * 只刷新去重后的坐标集合 —— 与批量套壳完全同一条性能约定。</p>
     *
     * <h2>守恒（用户本轮的核心要求：「批量取回」）</h2>
     * <p>归还判据与单格<b>同一份</b>（{@link SheathRemoval#refund()}）：只有「普通版 + 当初真的扣过」
     * 才还 1 个。无限版与创造模式套上的那一位取下时<b>一个都不产出</b> —— 用户原话
     * 「如果是无限的那个框架的话，把它全部取消掉就这样子」。于是
     * 「取回多少格就归还多少个普通框架」在批量路径上同样严格成立，一进一出永远相抵。</p>
     *
     * <p><b>幂等</b>：没套住的坐标直接跳过（不重复归还、不重复刷新）。</p>
     *
     * @return 真正被取下的格数；{@code 0} = 这一批都没套住（调用方据此把右键交回原版）
     */
    public static int removeAll(final ServerLevel level, final Player player, final Collection<BlockPos> positions) {
        if (positions.isEmpty()) {
            return 0;
        }
        final Saved saved = saved(level);
        // 先挑出「真的套着」的坐标：LinkedHashSet 顺带完成去重（同一坐标只取下一次、只还一个）
        final Set<BlockPos> targets = new LinkedHashSet<>();
        for (final BlockPos pos : positions) {
            if (pos != null && saved.sheaths.containsKey(pos)) {
                targets.add(pos);
            }
        }
        if (targets.isEmpty()) {
            return 0;
        }
        int refundable = 0;
        // 刷新集合 = 每个被取下的格子 + 它的六个邻格，最后各刷一次（等价逐格 refreshAround，但没有重复刷新）
        final Set<BlockPos> refreshTargets = new LinkedHashSet<>();
        for (final BlockPos pos : targets) {
            final Sheath removed = saved.sheaths.remove(pos);
            if (removed == null) {
                continue;
            }
            if (saved.paid.remove(pos) && !removed.infinite()) {
                refundable++; // 与单格 SheathRemoval#refund 逐字同一判据：普通版且当初真付过才还
            }
            refreshTargets.add(pos);
            for (final Direction direction : Direction.values()) {
                refreshTargets.add(pos.relative(direction));
            }
        }
        saved.setDirty();
        // 顺序要点同 remove：先把新快照发给客户端，再发方块更新 —— 客户端重算连接臂时用的已是新数据
        broadcast(level, saved);
        SeparationFrameGuard.refreshBlocks(level, refreshTargets);
        refund(player, refundable);
        return targets.size();
    }

    /**
     * 方块被<b>拆掉 / 被替换 / 重新放置</b>后清除该坐标的套壳记录（含快照），并把
     * <b>当初真的扣过的框架本体原样掉回世界</b>（用户要求：「分隔框架到底该怎么回收」）。
     *
     * <p>不清记录的话会出现「拆掉管道后重新放一段，还是保持套上前的旧状态」的鬼影，而且客户端会在一格空气外面画壳。
     * <p>只看这一个坐标：套壳的作用范围只有那一格自己，邻居的记录与它无关。
     *
     * <p><b>为什么必须掉回物品</b>：套壳不替换方块，被套的那一格一旦被拆掉（或 RS 扳手把整根线缆拆下），
     * 玩家花掉的那个框架就没有任何载体了 —— 只删记录就等于<b>凭空销毁</b>玩家的物品。
     * 这里用 {@code Block.popResource} 掉在那一格（原版掉落物，不需要玩家在场），
     * 且只掉「当初真的扣过」的那一位（{@code paid && !infinite}），创造模式 / 无限版绝不凭空产出。</p>
     */
    public static void onBlockChanged(final ServerLevel level, final BlockPos pos) {
        final Saved saved = saved(level);
        if (saved.sheaths.isEmpty()) {
            return; // 常见情况：从来没套过 —— 一次 isEmpty 即返回，零开销（paid 恒为 sheaths 的子集）
        }
        final Sheath removed = saved.sheaths.remove(pos);
        if (removed == null) {
            return;
        }
        final boolean paid = saved.paid.remove(pos);
        saved.setDirty();
        broadcast(level, saved);
        if (paid && !removed.infinite()) {
            Block.popResource(level, pos, new ItemStack(RS_Create_Compat.SEPARATION_FRAME_ITEM.get()));
        }
        SeparationFrameGuard.refreshAround(level, pos);
    }

    // ------------------------------------------------------------------
    // S2C 同步
    // ------------------------------------------------------------------

    /** 把当前维度的套壳快照整份下发给该玩家（登录 / 换维度 / 重生时调用）。 */
    public static void syncTo(final ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, buildPacket(saved(player.serverLevel())));
    }

    /** 客户端：整份替换镜像（由 S2C 快照调用；传入的是不可变列表）。 */
    public static void applyClientSnapshot(final List<SyncSheathPositionsPacket.Entry> entries) {
        if (entries.isEmpty()) {
            clientMirror = EMPTY;
            return;
        }
        final Map<BlockPos, Sheath> mirror = new HashMap<>(entries.size());
        for (final SyncSheathPositionsPacket.Entry entry : entries) {
            mirror.put(BlockPos.of(entry.pos()), new Sheath(entry.infinite(), entry.mask()));
        }
        clientMirror = Map.copyOf(mirror);
    }

    // ------------------------------------------------------------------
    // 客户端渲染查询
    // ------------------------------------------------------------------

    /** 客户端镜像里的全部被套坐标（渲染器遍历用；未收到快照时为空表）。 */
    public static Set<BlockPos> mirroredPositions() {
        final Map<BlockPos, Sheath> mirror = clientMirror;
        return mirror == null ? Set.of() : mirror.keySet();
    }

    /** 客户端镜像里该坐标是不是无限版（叠加附魔光泽用）。 */
    public static boolean mirroredInfinite(final BlockPos pos) {
        final Map<BlockPos, Sheath> mirror = clientMirror;
        final Sheath sheath = mirror == null ? null : mirror.get(pos);
        return sheath != null && sheath.infinite();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 服务端按维度的存档数据（键 = 方块坐标，值 = 无限版标记 + 连接快照；另有 paid 段见 {@link #paid}）。 */
    private static final class Saved extends SavedData {
        private static final SavedData.Factory<Saved> FACTORY = new SavedData.Factory<>(Saved::new, Saved::load);

        private final Map<BlockPos, Sheath> sheaths = new HashMap<>();

        /** 「当初真的扣过 1 个框架物品」的坐标集合；恒为 {@link #sheaths} 键集的子集（不变量）。 */
        private final Set<BlockPos> paid = new HashSet<>();

        /** 是否已给「老存档里没有快照的记录」补过一次快照（补齐需要 {@link ServerLevel}，故只能在服务端做）。 */
        private boolean migrated;

        private static Saved load(final CompoundTag tag, final HolderLookup.Provider provider) {
            final Saved saved = new Saved();
            final CompoundTag inner = tag.getCompound(KEY_SHEATHS);
            final CompoundTag masks = tag.getCompound(KEY_MASKS);
            final CompoundTag paidTags = tag.getCompound(KEY_PAID);
            for (final String packed : inner.getAllKeys()) {
                final long encoded;
                try {
                    encoded = Long.parseLong(packed);
                } catch (final NumberFormatException ignored) {
                    continue; // 单个坏键不影响其余数据（读档时静默跳过，不刷屏）
                }
                // 值只用来区分无限版：普通版写的是 byte 0，无限版写的是 boolean true。
                // 「键存在」本身才代表这一格被套住，所以两种取值都必须收下。
                // 快照单独放在 masks 段里：那里「键存在」= 有快照，缺键 = 本模组较早版本的记录（稍后补齐）。
                final boolean infinite = inner.getBoolean(packed);
                final int mask = masks.contains(packed) ? masks.getByte(packed) : NO_SNAPSHOT;
                final BlockPos pos = BlockPos.of(encoded);
                saved.sheaths.put(pos, new Sheath(infinite, mask));
                // paid 段是本轮（2026-09-25）新增的：缺键 = 老记录，按旧规则补齐
                // （旧规则里普通版一律扣过 1 个、无限版不扣），于是老存档的回收行为与改动前逐字一致。
                if (paidTags.contains(packed) ? paidTags.getBoolean(packed) : !infinite) {
                    saved.paid.add(pos);
                }
            }
            return saved;
        }

        @Override
        public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider provider) {
            final CompoundTag inner = new CompoundTag();
            final CompoundTag masks = new CompoundTag();
            final CompoundTag paidTags = new CompoundTag();
            sheaths.forEach((pos, sheath) -> {
                final String key = Long.toString(pos.asLong());
                // 普通版也必须写进去（值 0），否则读档后套壳会凭空消失
                if (sheath.infinite()) {
                    inner.putBoolean(key, true);
                } else {
                    inner.putByte(key, (byte) 0);
                }
                // 掩码 0 是合法值（六侧都不连），因此不能靠「值为 0」判断有没有快照 —— 靠键存在与否
                if (sheath.mask() >= 0) {
                    masks.putByte(key, (byte) sheath.mask());
                }
                // 「当初真的扣过物品」：两种取值都写（缺键有专门含义 = 老记录），见 load
                paidTags.putBoolean(key, paid.contains(pos));
            });
            tag.put(KEY_SHEATHS, inner);
            tag.put(KEY_MASKS, masks);
            tag.put(KEY_PAID, paidTags);
            return tag;
        }

        /**
         * 老存档兼容：给「没有快照」的记录补一次快照（只在服务端第一次取用本份数据时执行一次）。
         *
         * <p><b>为什么先置 {@code migrated} 再干活</b>：补快照的判定会再进 {@code saved(level)}
         * （读本格 / 邻格的记录），若不加这道闸就会递归。置位后重入会立刻拿到同一实例并返回。</p>
         *
         * <p><b>降级策略</b>：补某格快照时，<b>别的尚未补齐的老记录不参与阻断判定</b>
         * （{@link #sheathMask} 对 {@link #NO_SNAPSHOT} 返回 {@code null}），
         * 于是老套壳格按「补快照那一刻的真实连通情况」定格；两个相邻的老套壳格因此会保持它们本来就有的连接，
         * 而不是被旧的「套上即断开一切」规则永久锁死 —— 那条规则本轮已被删除。</p>
         */
        private void migrateIfNeeded(final ServerLevel level) {
            if (migrated) {
                return;
            }
            migrated = true;
            if (sheaths.isEmpty()) {
                return;
            }
            final List<BlockPos> legacy = new ArrayList<>();
            for (final Map.Entry<BlockPos, Sheath> entry : sheaths.entrySet()) {
                if (entry.getValue().mask() < 0) {
                    legacy.add(entry.getKey());
                }
            }
            if (legacy.isEmpty()) {
                return; // 常见情况：全部记录都带快照 —— 一次空扫描即返回
            }
            for (final BlockPos pos : legacy) {
                final Sheath sheath = sheaths.get(pos);
                if (sheath == null) {
                    continue;
                }
                sheaths.put(pos, new Sheath(sheath.infinite(), SeparationFrameGuard.snapshotMask(level, pos)));
            }
            setDirty(); // 补齐结果与存档同生共死，重进世界不会再来一遍
        }
    }

    private static Saved saved(final ServerLevel level) {
        final Saved saved = level.getDataStorage().computeIfAbsent(Saved.FACTORY, DATA_NAME);
        saved.migrateIfNeeded(level);
        return saved;
    }

    private static void broadcast(final ServerLevel level, final Saved saved) {
        final SyncSheathPositionsPacket packet = buildPacket(saved);
        for (final ServerPlayer player : level.players()) {
            PacketDistributor.sendToPlayer(player, packet);
        }
    }

    private static SyncSheathPositionsPacket buildPacket(final Saved saved) {
        final List<SyncSheathPositionsPacket.Entry> entries = saved.sheaths.entrySet().stream()
            .map(entry -> new SyncSheathPositionsPacket.Entry(
                entry.getKey().asLong(), entry.getValue().infinite(), entry.getValue().mask()))
            .toList();
        return new SyncSheathPositionsPacket(entries);
    }
}
