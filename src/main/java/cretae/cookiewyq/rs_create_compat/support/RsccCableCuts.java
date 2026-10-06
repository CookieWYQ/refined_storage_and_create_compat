package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.common.api.support.network.AbstractNetworkNodeContainerBlockEntity;
import com.refinedmods.refinedstorage.common.networking.CableConnections;
import com.refinedmods.refinedstorage.common.support.AbstractCableLikeBlockEntity;
import com.refinedmods.refinedstorage.common.util.PlatformUtil;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.advancement.RsccAdvancements;
import cretae.cookiewyq.rs_create_compat.network.SyncCableDisconnectsPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「机械动力扳手分离 RS 线缆」的唯一状态中心（服务端权威 + 客户端镜像）。
 *
 * <p><b>RS 2.0 的连接是怎么判定的（已逐一核对 RS 官方源码 jar，
 * {@code refinedstorage-neoforge-2.0.0-sources.jar}）</b></p>
 * <ul>
 *     <li>网络图连边由 {@code com.refinedmods.refinedstorage.common.support.network.ConnectionProviderImpl#getConnections}
 *     决定：对 {@code addOutgoingConnections} 出去的每个候选坐标，要求目标方的
 *     {@code InWorldNetworkNodeContainer#canAcceptIncomingConnection(dir, 来源方块状态)} 返回 true；</li>
 *     <li>线缆「连接臂」外形由 {@code AbstractCableLikeBlockEntity#computeConnections/hasVisualConnection}
 *     决定，用的<b>同一个</b> {@code canAcceptIncomingConnection} 判定，
 *     结果存进 {@code CableConnections}（6 个 boolean），由 {@code CableBlock#getShape} 与
 *     {@code CableBakedModel}（{@code ModelProperties.CABLE_CONNECTIONS}）消费；</li>
 *     <li>真正的策略实现是 {@code ColoredConnectionStrategy extends SimpleConnectionStrategy}
 *     （线缆 / 输入总线 / 输出总线统一走它，见 {@code AbstractBaseNetworkNodeContainerBlockEntity#createMainContainer}）。</li>
 * </ul>
 * <p>因此本项目<b>只在一个点接管</b>：{@code InWorldNetworkNodeContainerImpl#canAcceptIncomingConnection}
 * （见 {@code mixin/network/InWorldNetworkNodeContainerImplMixin}）——它同时是「网络图连边」与
 * 「连接臂外形」的共同闸门，一处断开即两侧同时生效，不会出现「看着断了但仍然串线」。</p>
 *
 * <p><b>被断开的记录（两种档位各一套，互不干扰）</b></p>
 * <ul>
 *     <li>{@link Mode#SEAM}（方式 A）：记录<b>归一化后的有向接缝键</b>
 *     ——坐标字典序较小的一侧 + 指向另一侧的方向。两侧查询同一个键，因此「在同一处接缝
 *     从任意一侧右键」得到同一条记录；</li>
 *     <li>{@link Mode#FACE}（方式 B）：记录<b>方块自身某个面不自动连接</b>。
 *     判定时同时检查两侧（本方的面 / 对面的反方向面），所以给任意一侧关面都能断开这一处连接。</li>
 * </ul>
 * <p>两者都用「坐标 → 6 位方向掩码」存储（位 = {@code Direction#ordinal()}），
 * 同一方块上的多条记录只占一个键。</p>
 *
 * <p><b>持久化</b>：服务端按维度存进 {@link SavedData}（数据文件名 {@value #DATA_NAME}，
 * 由 {@code DimensionDataStorage} 落到 {@code world/data/}），因此<b>读档后仍然断开</b>，
 * 且不需要给 RS 的方块实体或方块状态加任何字段（绝不破坏方块、绝不丢方块内数据）。</p>
 * <p><b>客户端</b>：客户端没有 {@code SavedData}，改由
 * {@code SyncCableDisconnectsPacket}（S2C 整份快照）维护一份只读镜像；
 * 该包在「玩家登录 / 换维度 / 重生 / 每次改动」时下发，且<b>先于方块更新发出</b>，
 * 因此客户端重算连接臂时用的已经是新数据。</p>
 *
 * <p>本类刻意放在 {@code support} 包：mixin 包被 mixins.json 声明为 Mixin 专用包，
 * 普通类放在其中被外部引用会抛 {@code IllegalClassLoadError}。</p>
 */
public final class RsccCableCuts {
    /** 存档数据文件名（{@code world/data/rscc_cable_cuts.dat}）。 */
    public static final String DATA_NAME = "rscc_cable_cuts";

    private static final String KEY_SEAM = "seam";
    private static final String KEY_FACE = "face";

    /** 客户端镜像（整份替换；未收到快照时为 null → 一律按「未断开」处理，等价 RS 原版行为）。 */
    private static volatile ClientMirror clientMirror;

    private RsccCableCuts() {
    }

    /** 扳手断线的生效档位。 */
    public enum Mode {
        /** 关闭：扳手完全不接管 RS 线缆。 */
        OFF,
        /** 方式 A：右键接缝断开 / 恢复这一处的自动连接。 */
        SEAM,
        /** 方式 B：右键线缆某个面，切换该面是否自动连接。 */
        FACE;

        /** 按配置字符串解析；无法识别时返回 {@code null}（由调用方决定回落值）。 */
        @Nullable
        public static Mode byName(@Nullable final String name) {
            if (name == null) {
                return null;
            }
            return switch (name.trim().toLowerCase(Locale.ROOT)) {
                case "seam" -> SEAM;
                case "face" -> FACE;
                case "off" -> OFF;
                default -> null;
            };
        }

        /** 按 S2C 里传的下标还原（越界回落到 {@link #OFF}）。 */
        public static Mode byOrdinal(final int ordinal) {
            final Mode[] values = values();
            return ordinal < 0 || ordinal >= values.length ? OFF : values[ordinal];
        }
    }

    /** 扳手右键的结果（用于给玩家一句短反馈）。 */
    public enum ToggleResult {
        /** 该处本来就没有可断开的连接（接缝档位才有）。 */
        INVALID,
        /** 本次操作把连接断开了。 */
        CUT,
        /** 本次操作把连接恢复了。 */
        RESTORED
    }

    // ------------------------------------------------------------------
    // 查询（网络图连边 + 连接臂外形 + 搜链 共用同一入口）
    // ------------------------------------------------------------------

    /**
     * {@code self} 朝 {@code towardOther} 这一处连接是否被扳手断开。
     *
     * <p><b>调用点</b>：{@code InWorldNetworkNodeContainerImpl#canAcceptIncomingConnection}
     * （{@code self} = 容器所属方块，{@code towardOther} = incomingDirection，
     * 即「从容器指向发起连接的那一方」）与 {@link RsccWireLinkSearch} 的每一步。</p>
     */
    public static boolean isDisconnected(final Level level, final BlockPos self, final Direction towardOther) {
        final Mode mode;
        final Map<BlockPos, Integer> seamMasks;
        final Map<BlockPos, Integer> faceMasks;
        if (level instanceof final ServerLevel serverLevel) {
            mode = Config.wrenchCableDisconnectMode;
            if (mode == Mode.OFF) {
                return false;
            }
            final Saved saved = saved(serverLevel);
            seamMasks = saved.seam;
            faceMasks = saved.face;
        } else {
            final ClientMirror mirror = clientMirror;
            if (mirror == null) {
                return false;
            }
            mode = mirror.mode();
            seamMasks = mirror.seam();
            faceMasks = mirror.face();
        }
        if (mode == Mode.OFF) {
            return false;
        }
        final BlockPos other = self.relative(towardOther);
        if (mode == Mode.SEAM) {
            if (seamMasks.isEmpty()) {
                return false;
            }
            // 接缝按「坐标字典序较小的一侧 + 指向另一侧」归一化：两侧查询命中同一个键
            return self.asLong() <= other.asLong()
                ? has(seamMasks, self, towardOther)
                : has(seamMasks, other, towardOther.getOpposite());
        }
        if (faceMasks.isEmpty()) {
            return false;
        }
        // 任一侧把这个面关掉，这一处连接即断开
        return has(faceMasks, self, towardOther) || has(faceMasks, other, towardOther.getOpposite());
    }

    /** 该维度当前生效的档位（服务端读配置，客户端优先用服务端下发的档位）。 */
    public static Mode modeFor(final Level level) {
        if (level instanceof ServerLevel) {
            return Config.wrenchCableDisconnectMode;
        }
        final ClientMirror mirror = clientMirror;
        return mirror != null ? mirror.mode() : Config.wrenchCableDisconnectMode;
    }

    /**
     * 统计给定包围盒内<b>有多少处线缆断开</b>（只读；蓝图导出提示用）。
     *
     * <h2>为什么需要它（缺口 1：断开记录不进蓝图）</h2>
     * <p>扳手断开同样只存在本模组的 {@link SavedData} 里（{@link Mode#SEAM} 记接缝、{@link Mode#FACE}
     * 记面），而 Create 的蓝图只写 {@code StructureTemplate}（方块 + 方块实体）——
     * 导出 / 粘贴后断开必然全丢，两条线缆会重新连上，玩家的总线归属随之从「唯一」变成「≥2 台」而被停用，
     * 玩家却只看到「总线莫名其妙退回普通界面」。这里只提供「这一次导出会丢掉多少处」这个<b>事实</b>。</p>
     *
     * <h2>计数口径（两种档位都算「一处断开」）</h2>
     * <ul>
     *     <li><b>接缝</b>（方式 A）：记录键是「坐标字典序较小的一侧 + 指向另一侧」，只要这条接缝的
     *     <b>任意一端</b>落在选择区内就算一处 —— 那一端正是蓝图会带走的那根线缆，
     *     接缝丢了，它在粘贴后就可能与邻居重新连上；</li>
     *     <li><b>面</b>（方式 B）：记录在「这个方块的某个面不自动连接」，方块在选择区内即算一处；</li>
     *     <li>同一档位下「一处操作 = 一条记录」，因此数量就是玩家按过的次数，不存在重复计数；
     *     档位为 {@link Mode#OFF} 时断开本来就不生效（{@link #isDisconnected} 恒返回 false），
     *     因此计 0 —— 不把「没生效的设置」算成会丢的东西。</li>
     * </ul>
     *
     * <p><b>双端同源</b>：服务端读权威存档数据，客户端读 {@code SyncCableDisconnectsPacket} 的整份镜像，
     * 两边同一个口径。</p>
     *
     * @param box 蓝图选择区，{@code null} = 不统计
     * @return 断开处数（接缝 + 面）；档位关闭 / 没有记录时返回 0
     */
    public static int countCutsIn(final Level level, @Nullable final BoundingBox box) {
        if (level == null || box == null || modeFor(level) == Mode.OFF) {
            return 0;
        }
        final Map<BlockPos, Integer> seam;
        final Map<BlockPos, Integer> face;
        if (level instanceof final ServerLevel serverLevel) {
            final Saved saved = saved(serverLevel);
            seam = saved.seam;
            face = saved.face;
        } else {
            final ClientMirror mirror = clientMirror;
            if (mirror == null) {
                return 0; // 还没收到镜像：一律按「没有断开」处理（与 isDisconnected 同一降级口径）
            }
            seam = mirror.seam();
            face = mirror.face();
        }
        int count = 0;
        for (final Map.Entry<BlockPos, Integer> entry : seam.entrySet()) {
            final BlockPos owner = entry.getKey();
            final int mask = entry.getValue();
            for (final Direction direction : Direction.values()) {
                if ((mask & bit(direction)) == 0) {
                    continue; // 这个方向没断
                }
                // 接缝的「另一端」= owner.relative(direction)；任意一端落在选择区内即算一处
                if (box.isInside(owner) || box.isInside(owner.relative(direction))) {
                    count++;
                }
            }
        }
        for (final BlockPos pos : face.keySet()) {
            if (box.isInside(pos)) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // 交互（仅服务端调用）
    // ------------------------------------------------------------------

    /**
     * 方式 A：断开 / 恢复「{@code pos} 与 {@code pos.relative(dir)} 之间的接缝」。
     *
     * <p><b>准入条件（用户本轮确认的语义：断开点就是两根线缆之间那一段接缝）</b>：</p>
     * <ol>
     *     <li>这一处<b>当前确实长着连接臂</b>（读 {@code AbstractCableLikeBlockEntity#getConnections}
     *     的外形数据）—— 沿用旧行为，任何邻居类型都算；</li>
     *     <li><b>或者</b>两侧都是 RS 线缆族方块（{@link RsccWireBlocks#isWire}）——
     *     用户明确要求「<b>所有这样的间隙都应该能被断开</b>，不是非得围起来才行」，
     *     因此两根线缆之间的那道缝永远可断（哪怕此刻外形上还没有臂）。</li>
     * </ol>
     * <p>已经由本功能断开的接缝可直接恢复（此时外形数据本来就已是「不连」，不能再拿它做校验）。</p>
     */
    public static ToggleResult toggleSeam(final ServerLevel level, final BlockPos pos, final Direction dir) {
        final BlockPos other = pos.relative(dir);
        final Saved saved = saved(level);
        final boolean posIsLower = pos.asLong() <= other.asLong();
        final BlockPos owner = posIsLower ? pos : other;
        final Direction ownerDir = posIsLower ? dir : dir.getOpposite();
        final boolean wasCut = has(saved.seam, owner, ownerDir);
        if (!wasCut && !canCutSeam(level, pos, dir)) {
            return ToggleResult.INVALID;
        }
        toggle(saved.seam, owner, ownerDir);
        saved.setDirty();
        // 顺序要点：先把新状态发给客户端，再发方块更新 —— 客户端重算连接臂时用的已是新数据
        broadcast(level, saved);
        refresh(level, pos);
        refresh(level, other);
        // 成就触发点：断开 / 接回一道线缆缝（服务端；wasCut 区分两件事，各对应一条成就）
        RsccAdvancements.onSeamToggled(level, pos, wasCut);
        return wasCut ? ToggleResult.RESTORED : ToggleResult.CUT;
    }

    /**
     * 这一处接缝当前是否允许被切断（两条准入之一即可，见 {@link #toggleSeam} 的注释）。
     * <p>坐标未加载时直接返回 false（绝不为了一句判定去加载区块）。</p>
     *
     * <p><b>为什么是 public 且收 {@link Level} 而不是 {@code ServerLevel}</b>：客户端预览
     * （{@code CableCutPreviewOverlay}）与选缝入口（{@link RsccSeamPick}）必须用<b>同一句准入</b>
     * 才能保证「预览画出来的缝 == 服务端真正切的缝」。本方法只读方块状态、方块实体的外形数据与
     * 客户端镜像，双端语义完全一致，因此没有必要（也不应该）在客户端再抄一份判定。</p>
     *
     * <p><b>为什么直线中间与端头都能断</b>：直线<b>中间</b>的缝两侧都长着连接臂
     * （{@link #hasVisibleConnection} 命中第一条准入）；直线<b>端头</b>那道缝两侧虽然也是线缆，
     * 但靠的是第二条准入「两侧都是线缆族」——两条准入任一成立即可，
     * 因此一长串直线上的<b>每一道缝</b>（含端头那道）都能单独断开。</p>
     */
    public static boolean canCutSeam(final Level level, final BlockPos pos, final Direction dir) {
        if (hasVisibleConnection(level, pos, dir)) {
            return true;
        }
        final BlockPos other = pos.relative(dir);
        if (!level.isLoaded(pos) || !level.isLoaded(other)) {
            return false;
        }
        return RsccWireBlocks.isWire(level.getBlockState(pos))
            && RsccWireBlocks.isWire(level.getBlockState(other));
    }

    /**
     * 这一处接缝此刻「按下去真的会发生点什么」：要么它本来就被本功能断开（按下去是恢复），
     * 要么它是可切的（见 {@link #canCutSeam}）。
     *
     * <p>这正是服务端 {@link #toggleSeam} 的准入条件（{@code wasCut || canCutSeam}）被抽出来的一份 ——
     * 选缝（{@link RsccSeamPick}）、客户端预览与服务端结算三处共用它，
     * 于是「选出来的缝一定不会回 INVALID」这件事有唯一来源，不会三处各写一半而漂移。</p>
     */
    public static boolean canToggleSeam(final Level level, final BlockPos pos, final Direction dir) {
        return isDisconnected(level, pos, dir) || canCutSeam(level, pos, dir);
    }

    /**
     * 方式 B：切换「{@code pos} 的 {@code face} 面是否自动连接」。
     * <p>不要求该处当前有连接：先把面关掉，之后邻块再放上来也不会自动连（这才是「该面不自动连接」的语义）。</p>
     */
    public static ToggleResult toggleFace(final ServerLevel level, final BlockPos pos, final Direction face) {
        final Saved saved = saved(level);
        final boolean wasCut = has(saved.face, pos, face);
        toggle(saved.face, pos, face);
        saved.setDirty();
        broadcast(level, saved);
        refresh(level, pos);
        refresh(level, pos.relative(face));
        return wasCut ? ToggleResult.RESTORED : ToggleResult.CUT;
    }

    /**
     * 方块被<b>拆掉 / 被替换 / 重新放置</b>后，清除与这个位置相关的全部断开记录
     * （用户实测 bug：断开后把方块拆掉再放回来，仍然连不上 —— 根因是断点记录按坐标永久残留在存档里）。
     *
     * <p><b>清理范围（三处，缺一不可）</b>：</p>
     * <ol>
     *     <li>本位置的记录：接缝（它可能是接缝键的「owner」那一侧）+ 面（那个面已随方块消失）；</li>
     *     <li>六个邻块「指向本位置」的记录：接缝位（邻块是 owner 时键存在邻块上）+ 面位
     *     （邻块那个面记着「不与该位置自动连接」）；</li>
     *     <li>掩码归零的键顺手移除（不留空记录，存档不堆积）。</li>
     * </ol>
     * <p><b>为什么这样就能「重新放置即恢复自动连接」</b>：把方块放回来时（{@code EntityPlaceEvent}）
     * 记录已被清空，{@code canAcceptIncomingConnection} 立刻返回 true，RS 按原版逻辑自动连边 / 长臂。</p>
     * <p><b>扛住读档</b>：改动走 {@link SavedData#setDirty()}，清理结果与存档同生共死，
     * 重进世界不会让脏记录复活。</p>
     * <p><b>立刻生效 / 双端一致</b>：先 {@code broadcast} 把新快照发给客户端，
     * 再 {@code refresh} 本位置与六个邻块的连接臂 + 网络图 —— 与扳手切换走的是同一条链路。</p>
     * <p>调用方只使用「玩家主动破坏 / 放置」事件与爆炸事件，<b>不会</b>被本模组自己的刷新动作触发，
     * 因此不会把自己刚写下的断开记录误清掉。</p>
     */
    public static void onBlockChanged(final ServerLevel level, final BlockPos pos) {
        if (Config.wrenchCableDisconnectMode == Mode.OFF) {
            return;
        }
        final Saved saved = saved(level);
        if (saved.seam.isEmpty() && saved.face.isEmpty()) {
            return; // 常见情况：从没断过线 —— 两次 isEmpty 即返回，零开销
        }
        boolean changed = saved.seam.remove(pos) != null;
        changed |= saved.face.remove(pos) != null;
        for (final Direction direction : Direction.values()) {
            final Direction towardPos = direction.getOpposite();
            changed |= clearBit(saved.seam, pos.relative(direction), towardPos);
            changed |= clearBit(saved.face, pos.relative(direction), towardPos);
        }
        if (!changed) {
            return;
        }
        saved.setDirty();
        broadcast(level, saved);
        refresh(level, pos);
        for (final Direction direction : Direction.values()) {
            refresh(level, pos.relative(direction));
        }
    }

    /** 把当前维度的断开快照整份下发给该玩家（登录 / 换维度 / 重生时调用）。 */
    public static void syncTo(final ServerPlayer player) {
        final ServerLevel level = player.serverLevel();
        final Saved saved = saved(level);
        PacketDistributor.sendToPlayer(player, buildPacket(saved));
    }

    /** 客户端：整份替换镜像（由 S2C 快照调用；传入的是不可变拷贝）。 */
    public static void applyClientSnapshot(final int modeOrdinal,
                                           final List<SyncCableDisconnectsPacket.Entry> seam,
                                           final List<SyncCableDisconnectsPacket.Entry> face) {
        clientMirror = new ClientMirror(Mode.byOrdinal(modeOrdinal), toMasks(seam), toMasks(face));
    }

    // ------------------------------------------------------------------
    // 内部：存储
    // ------------------------------------------------------------------

    /** 服务端按维度的存档数据（键 = 方块坐标，值 = 6 位方向掩码）。 */
    private static final class Saved extends SavedData {
        private static final SavedData.Factory<Saved> FACTORY = new SavedData.Factory<>(Saved::new, Saved::load);

        private final Map<BlockPos, Integer> seam = new HashMap<>();
        private final Map<BlockPos, Integer> face = new HashMap<>();

        private static Saved load(final CompoundTag tag, final HolderLookup.Provider provider) {
            final Saved saved = new Saved();
            readMasks(tag, KEY_SEAM, saved.seam);
            readMasks(tag, KEY_FACE, saved.face);
            return saved;
        }

        @Override
        public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider provider) {
            tag.put(KEY_SEAM, writeMasks(seam));
            tag.put(KEY_FACE, writeMasks(face));
            return tag;
        }
    }

    private static Saved saved(final ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Saved.FACTORY, DATA_NAME);
    }

    private static CompoundTag writeMasks(final Map<BlockPos, Integer> masks) {
        final CompoundTag tag = new CompoundTag();
        masks.forEach((pos, mask) -> tag.putByte(Long.toString(pos.asLong()), mask.byteValue()));
        return tag;
    }

    private static void readMasks(final CompoundTag tag, final String key, final Map<BlockPos, Integer> out) {
        final CompoundTag source = tag.getCompound(key);
        for (final String packed : source.getAllKeys()) {
            final int mask = source.getByte(packed);
            if (mask == 0) {
                continue;
            }
            try {
                out.put(BlockPos.of(Long.parseLong(packed)), mask);
            } catch (final NumberFormatException ignored) {
                // 单个坏键不影响其余数据（不刷屏，读档时静默跳过）
            }
        }
    }

    private static int bit(final Direction direction) {
        return 1 << direction.ordinal();
    }

    private static boolean has(final Map<BlockPos, Integer> masks, final BlockPos pos, final Direction direction) {
        final Integer mask = masks.get(pos);
        return mask != null && (mask & bit(direction)) != 0;
    }

    /** 翻转某方块某方向的位；掩码归零时顺手移除该键，避免存档里堆积空记录。 */
    private static void toggle(final Map<BlockPos, Integer> masks, final BlockPos pos, final Direction direction) {
        final int now = masks.getOrDefault(pos, 0) ^ bit(direction);
        if (now == 0) {
            masks.remove(pos);
        } else {
            masks.put(pos, now);
        }
    }

    /** 清除某方块某方向的位；真的清掉了才返回 true（掩码归零时移除该键）。 */
    private static boolean clearBit(final Map<BlockPos, Integer> masks, final BlockPos pos,
                                    final Direction direction) {
        final Integer mask = masks.get(pos);
        if (mask == null || (mask & bit(direction)) == 0) {
            return false;
        }
        final int now = mask & ~bit(direction);
        if (now == 0) {
            masks.remove(pos);
        } else {
            masks.put(pos, now);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 内部：RS 侧刷新（外形 + 网络图 + 方块更新）
    // ------------------------------------------------------------------

    /** 该方块朝该方向当前是否有「连接臂」（直接读 RS 的外形数据，不做任何推断）。 */
    private static boolean hasVisibleConnection(final Level level, final BlockPos pos, final Direction direction) {
        final BlockEntity blockEntity = existingBlockEntity(level, pos);
        if (!(blockEntity instanceof final AbstractCableLikeBlockEntity<?> cable)) {
            return false;
        }
        return hasArm(direction, cable.getConnections());
    }

    private static boolean hasArm(final Direction direction, final CableConnections connections) {
        return switch (direction) {
            case DOWN -> connections.down();
            case UP -> connections.up();
            case NORTH -> connections.north();
            case SOUTH -> connections.south();
            case WEST -> connections.west();
            case EAST -> connections.east();
        };
    }

    /**
     * 让 RS 把这一格的连接重新算一遍：
     * <ol>
     *     <li>{@code updateConnections()}：重算连接臂外形（{@code CableConnections}）；
     *     <li>{@code getContainerProvider().update(level)}：按 RS 官方注释推荐的
     *     {@code RefinedStorageApi#updateNetworkNodeContainer} 重建网络图（断开 / 合并网络）；
     *     <li>{@code sendBlockUpdateToClient}：把新的外形数据（方块实体 NBT）推给客户端。
     * </ol>
     * <p>全部用 {@code CHECK} 取「已存在」的方块实体，方块实体不存在 / 坐标未加载时直接跳过，
     * 绝不强制加载、绝不改动方块本身。</p>
     * <p><b>为什么是 public</b>：分隔框架（{@link SeparationFrameGuard#refreshAround}）在
     * 放置 / 拆除后需要同一套「重算连接 + 重算网络图 + 下发方块更新」，此处复用，
     * 避免两处各写一遍 RS 内部调用而在将来各自漂移。</p>
     */
    public static void refresh(final ServerLevel level, final BlockPos pos) {
        final BlockEntity blockEntity = existingBlockEntity(level, pos);
        if (blockEntity == null) {
            return;
        }
        if (blockEntity instanceof final AbstractCableLikeBlockEntity<?> cable) {
            cable.updateConnections();
        }
        if (blockEntity instanceof final AbstractNetworkNodeContainerBlockEntity<?> node) {
            node.getContainerProvider().update(level);
        }
        PlatformUtil.sendBlockUpdateToClient(level, pos);
    }

    @Nullable
    private static BlockEntity existingBlockEntity(final Level level, final BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        return level.getChunkAt(pos).getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
    }

    // ------------------------------------------------------------------
    // 内部：S2C 同步
    // ------------------------------------------------------------------

    private static void broadcast(final ServerLevel level, final Saved saved) {
        final SyncCableDisconnectsPacket packet = buildPacket(saved);
        for (final ServerPlayer player : level.players()) {
            PacketDistributor.sendToPlayer(player, packet);
        }
    }

    private static SyncCableDisconnectsPacket buildPacket(final Saved saved) {
        return new SyncCableDisconnectsPacket(
            Config.wrenchCableDisconnectMode.ordinal(), toEntries(saved.seam), toEntries(saved.face));
    }

    private static List<SyncCableDisconnectsPacket.Entry> toEntries(final Map<BlockPos, Integer> masks) {
        final List<SyncCableDisconnectsPacket.Entry> list = new ArrayList<>(masks.size());
        masks.forEach((pos, mask) -> list.add(
            new SyncCableDisconnectsPacket.Entry(pos.asLong(), mask.byteValue())));
        return list;
    }

    private static Map<BlockPos, Integer> toMasks(final List<SyncCableDisconnectsPacket.Entry> entries) {
        final Map<BlockPos, Integer> masks = new HashMap<>();
        for (final SyncCableDisconnectsPacket.Entry entry : entries) {
            if (entry.mask() != 0) {
                masks.put(BlockPos.of(entry.pos()), (int) entry.mask());
            }
        }
        return Map.copyOf(masks);
    }

    /** 客户端镜像：档位 + 两套掩码，整份替换。 */
    private record ClientMirror(Mode mode, Map<BlockPos, Integer> seam, Map<BlockPos, Integer> face) {
    }
}
