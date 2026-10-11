package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
import cretae.cookiewyq.rs_create_compat.data.SequencePatternData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * S2C：新语义（v4）—— 把每一步的「已指派机器名 + recipeType + 是否已指派」同步给客户端。
 * <p>机器指派只存在于服务端（终端方块实体的 {@code stepMachinePos/stepMachineNames}），
 * 客户端没有写入权，因此需要本包把渲染「◀ 机器名 ▶」所需的数据送过来：
 * 容器打开时、以及每次设置机器 / 增删步骤之后，服务端都会重发一次全量快照。</p>
 * <p>随包下发的还有两个<b>服务端权威</b>的布尔事实（客户端只读展示，判据都在服务端）：</p>
 * <ul>
 *     <li>{@code unitEquipped}：该步的机器 / 样板是否已就位 —— 行内「已有 / 没有」显示的就是它；</li>
 *     <li>{@code duplicateExists}：是否已存在语义完全相同的单元样板 —— tooltip 里单列，
 *     说明「生成时会跳过重复」这一事实。</li>
 * </ul>
 * <p>客户端只做镜像缓存（{@link #getLastReceived()}），不参与任何权威写入。</p>
 *
 * <h2>第 49 轮：快照分「轻」「重」两条路（开界面不再被全量扫描拖住）</h2>
 * <p><b>病情</b>：{@link #from} 里那两个布尔事实要遍历<b>整张 RS 网络</b>（每个执行舱的每个单元槽
 * 逐张比对样板 NBT）再逐步骤算一次，另外还会调一次<b>老样板自愈</b>（逐步骤查配方、必要时还要扫
 * 全部序列装配配方）。这三件事原先全部发生在「菜单打开后第 1 拍」——单人游戏里客户端与服务端共用
 * 一条主线程，于是玩家看到的正是「按快捷键之后界面迟迟不出来（有时几百毫秒）」，且开销随
 * <b>网络节点数 / 执行舱数 / 单元样板数 / 步骤数</b>变化，所以「有时候不卡、有时候很卡」。</p>
 * <p><b>处方</b>：打开界面那一拍只发 {@link #fromCheap 轻快照}（逐步骤读一次样板 NBT + 读判重缓存，
 * 不遍历网络、不查配方），把两个昂贵的布尔事实交给 {@code SequencePatternTerminalMenu} 在
 * {@value #DISPLAY_CACHE_TICKS} 拍的新鲜期内<b>至多扫一次</b>的 {@link #from 权威快照}去补。
 * 两个事实都只用于<b>显示</b>（生成侧在 {@code generateAssemblyPattern()} 里自己重扫，权威不变），
 * 因此延后补齐不改变任何判定。</p>
 */
public record SyncStepMachinesPacket(List<Entry> steps) implements CustomPacketPayload {
    /**
     * 单个步骤的同步项。
     *
     * @param stepIndex       全局步骤下标（与终端 arrangement 下标一致）
     * @param hasPos          该步是否已显式指派机器
     * @param machineName     已指派机器名（未指派为 ""）
     * @param recipeType      该步单元样板的配方类型 id（为空串表示未知）
     * @param duplicateExists 该步<b>是否已存在语义完全相同的单元样板</b>（服务端扫描结果；供 tooltip 说明
     *                        「生成时会跳过重复」这一事实 —— 判据是「同配方 + 同步序」，与生成侧同源）
     * @param skipDuplicate   该步<b>生成时是否跳过重复</b>（每步开关；客户端据此画行内开关的底色）
     * @param unitEquipped    该步<b>机器 / 样板是否已就位</b>（服务端扫描结果；客户端行内「已有 / 没有」
     *                        显示的就是这一位，见 {@code SequencePatternTerminalBlockEntity#stepUnitEquipped}）
     */
    public record Entry(int stepIndex, boolean hasPos, String machineName, String recipeType,
                        boolean duplicateExists, boolean skipDuplicate, boolean unitEquipped) {
        /**
         * 手写编解码（{@code StreamCodec.composite} 最多 6 个分量，本记录已有 7 个）。
         * 逐字段用既有 {@link ByteBufCodecs} 编解码，遵守本工程「>6 分量就手写」的既有做法
         * （见 {@code support/MarkerEntry}），两端字段顺序一一对应。
         */
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.of(
            (buf, entry) -> {
                ByteBufCodecs.VAR_INT.encode(buf, entry.stepIndex());
                ByteBufCodecs.BOOL.encode(buf, entry.hasPos());
                ByteBufCodecs.STRING_UTF8.encode(buf, entry.machineName());
                ByteBufCodecs.STRING_UTF8.encode(buf, entry.recipeType());
                ByteBufCodecs.BOOL.encode(buf, entry.duplicateExists());
                ByteBufCodecs.BOOL.encode(buf, entry.skipDuplicate());
                ByteBufCodecs.BOOL.encode(buf, entry.unitEquipped());
            },
            buf -> new Entry(
                ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.BOOL.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.BOOL.decode(buf),
                ByteBufCodecs.BOOL.decode(buf),
                ByteBufCodecs.BOOL.decode(buf))
        );
    }

    public static final Type<SyncStepMachinesPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_step_machines"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncStepMachinesPacket> STREAM_CODEC =
        StreamCodec.composite(
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), SyncStepMachinesPacket::steps,
            SyncStepMachinesPacket::new
        );

    /** 客户端最近一次收到的步骤机器快照（不可变；永不为 null）。 */
    private static volatile List<Entry> lastReceived = List.of();
    /**
     * 上一条快照的<b>步骤下标 → 条目</b>索引（{@link #stepAt} 用）。
     * <p><b>为什么要预建这张表</b>：界面每帧渲染 8 行，每行要问 6 次「这一步的条目是什么」，
     * 而旧实现每次都从 {@link #lastReceived} <b>线性扫</b>一遍（S 步 × 8 行 × 6 次 = O(S²) 级别）。
     * 快照本身只有 S 个条目，收包时一次性建成 Map 后每问一次都是 O(1)，且只在收包时重建一次。</p>
     */
    private static volatile Map<Integer, Entry> lastReceivedByStep = Map.of();

    /** 客户端最近一次收到的步骤机器快照（可能为空列表）。 */
    public static List<Entry> getLastReceived() {
        return lastReceived;
    }

    /** 从快照里取某一步的条目（没有则返回 null）；O(1) 查表，不再线性扫描。 */
    public static Entry stepAt(final int stepIndex) {
        return lastReceivedByStep.get(stepIndex);
    }

    /** 由终端方块实体构造全量快照（0 .. arrangementSize-1，含两个昂贵事实）。 */
    public static SyncStepMachinesPacket from(final SequencePatternTerminalBlockEntity terminal) {
        if (terminal == null) {
            return new SyncStepMachinesPacket(List.of());
        }
        // 先把「每一步是否已有相同单元样板」的扫描缓存刷新到最新，再据此下发（客户端只读展示）
        terminal.refreshStepDuplicateCache();
        // **老样板自愈**（2026-10-05）：修复之前导入 / 生成的样板 NBT 里没有 InputCandidates，
        // 界面因此永远只显示代表物（用户实测：「铁粒还是只显示个铁粒」）。
        // 界面一打开就按配方把候选组补算写回 —— 幂等（已有候选的步直接跳过），因此不必让玩家重新导入。
        // 第 49 轮起加节流（见 healIfDue）：它逐步骤查配方、必要时还要扫全部序列装配配方，
        // 是「开界面慢」里唯一<b>与网络规模无关的固定开销</b>，绝不该每次开界面都重跑。
        healIfDue(terminal);
        final HolderLookup.Provider registries = terminal.getLevel() != null
            ? terminal.getLevel().registryAccess() : net.minecraft.core.RegistryAccess.EMPTY;
        final List<Entry> entries = new ArrayList<>(terminal.arrangementSize);
        final boolean[] equipped = new boolean[terminal.arrangementSize];
        for (int i = 0; i < terminal.arrangementSize; i++) {
            // 走方块实体的唯一读入口：导入后是展示数据，老存档是真实编排容器（见 arrangementView()）
            final ItemStack unit = terminal.arrangementUnit(i);
            final String recipeType = SequencePatternData.readUnitNew(unit, registries).recipeType();
            // 行内「已有 / 没有」= 该步的机器 / 样板是否已就位（链级；与判重是两个不同的问题，
            // 见 SequencePatternTerminalBlockEntity#stepUnitEquipped 的 javadoc）
            final boolean unitEquipped = terminal.stepUnitEquipped(i);
            equipped[i] = unitEquipped;
            entries.add(new Entry(i,
                terminal.getStepMachinePos(i) != null,
                terminal.getStepMachineName(i),
                recipeType == null ? "" : recipeType,
                terminal.stepDuplicateExists(i),
                terminal.getStepSkipDuplicate(i),
                unitEquipped));
        }
        storeEquipped(terminal, equipped);
        stampAuthoritative(terminal);
        return new SyncStepMachinesPacket(entries);
    }

    /**
     * 由终端方块实体构造<b>轻快照</b>（打开界面那一拍专用）：只读每步样板的 NBT 与两个既有缓存，
     * <b>不</b>遍历网络、<b>不</b>查配方、<b>不</b>写任何东西。
     * <p>代价与「步骤数」成正比（每步一次样板 NBT 读取），与网络节点数 / 执行舱数 / 样板总数<b>无关</b>，
     * 因此开界面的耗时不再随存档规模波动。两个昂贵事实读的是上一次权威快照留下的缓存
     * （{@link #EQUIPPED} 与 {@code stepDuplicateCache}），冷缓存时先按「未知 = 没有」下发，
     * 数拍后由权威快照纠正。</p>
     */
    public static SyncStepMachinesPacket fromCheap(final SequencePatternTerminalBlockEntity terminal) {
        if (terminal == null) {
            return new SyncStepMachinesPacket(List.of());
        }
        final HolderLookup.Provider registries = terminal.getLevel() != null
            ? terminal.getLevel().registryAccess() : net.minecraft.core.RegistryAccess.EMPTY;
        final List<Entry> entries = new ArrayList<>(terminal.arrangementSize);
        for (int i = 0; i < terminal.arrangementSize; i++) {
            final ItemStack unit = terminal.arrangementUnit(i);
            final String recipeType = SequencePatternData.readUnitNew(unit, registries).recipeType();
            entries.add(new Entry(i,
                terminal.getStepMachinePos(i) != null,
                terminal.getStepMachineName(i),
                recipeType == null ? "" : recipeType,
                terminal.stepDuplicateExists(i),
                terminal.getStepSkipDuplicate(i),
                cachedEquipped(terminal, i)));
        }
        return new SyncStepMachinesPacket(entries);
    }

    public static void handle(final SyncStepMachinesPacket packet,
                              final net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            final List<Entry> copy = List.copyOf(packet.steps());
            // 先建索引再发布：两个 volatile 字段各自可见，读侧永远拿得到与列表同源的 Map
            lastReceivedByStep = buildIndex(copy);
            lastReceived = copy;
        });
    }

    /** 收包时一次性建成「步骤下标 → 条目」索引（下标重复时保留先到的那一条）。 */
    private static Map<Integer, Entry> buildIndex(final List<Entry> steps) {
        final Map<Integer, Entry> index = new java.util.HashMap<>(Math.max(4, steps.size() * 2));
        for (final Entry entry : steps) {
            index.putIfAbsent(entry.stepIndex(), entry);
        }
        return Map.copyOf(index);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ==================== 服务端：昂贵事实的缓存与节流（第 49 轮） ====================

    /**
     * 权威快照结果的新鲜期（拍）。{@value #DISPLAY_CACHE_TICKS} 拍（3 秒）内重新打开终端界面
     * <b>一次网络扫描都不做</b>：两个布尔事实只用于显示，3 秒的滞后不会误导玩家
     * （生成侧每次生成前都会自己重扫，权威判定一个字都不依赖这份缓存）。
     */
    public static final int DISPLAY_CACHE_TICKS = 60;

    /** 老样板自愈的最小间隔（拍）：30 秒（自愈是幂等的写操作，没必要每次开界面都扫配方表）。 */
    private static final int HEAL_INTERVAL_TICKS = 600;

    /**
     * 「每一步是否已就位」的最近一次权威结果（弱键：方块实体卸载后自动回收，不会跨存档泄漏）。
     * <p>只在服务端主线程读写；用同步包装是为极端情况（其它模组从别的线程读方块实体）留的余地。</p>
     */
    private static final Map<BlockEntity, boolean[]> EQUIPPED =
        Collections.synchronizedMap(new WeakHashMap<>());
    /** 每个终端最近一次权威扫描的拍号（TTL 判定用）。 */
    private static final Map<BlockEntity, Long> AUTHORITATIVE_STAMP =
        Collections.synchronizedMap(new WeakHashMap<>());
    /** 每个终端最近一次「老样板自愈」的拍号（节流用）。 */
    private static final Map<BlockEntity, Long> HEAL_STAMP =
        Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 该终端的显示缓存是否还算新鲜（距上一次权威扫描不足 {@link #DISPLAY_CACHE_TICKS} 拍）。
     * <p>拍号倒退（换维度 / 换存档）时一律判「不新鲜」，宁可贵一次也不显示旧存档的结论。</p>
     */
    public static boolean displayCacheFresh(final BlockEntity terminal, final long gameTime) {
        final Long stamp = AUTHORITATIVE_STAMP.get(terminal);
        if (stamp == null) {
            return false;
        }
        final long age = gameTime - stamp;
        return age >= 0L && age < DISPLAY_CACHE_TICKS;
    }

    /** 缓存某终端本次权威扫描算出的「已就位」位图。 */
    private static void storeEquipped(final BlockEntity terminal, final boolean[] equipped) {
        EQUIPPED.put(terminal, equipped);
    }

    /** 记录一次权威扫描的拍号（取终端所在世界的游戏时间；没有世界时按 0 处理）。 */
    private static void stampAuthoritative(final BlockEntity terminal) {
        final net.minecraft.world.level.Level level = terminal.getLevel();
        if (level != null) {
            AUTHORITATIVE_STAMP.put(terminal, level.getGameTime());
        }
    }

    /** 读缓存的「已就位」位：没有缓存 / 越界 ⇒ false（显示为「没有」，数拍后由权威快照纠正）。 */
    private static boolean cachedEquipped(final BlockEntity terminal, final int stepIndex) {
        final boolean[] flags = EQUIPPED.get(terminal);
        return flags != null && stepIndex >= 0 && stepIndex < flags.length && flags[stepIndex];
    }

    /**
     * 老样板自愈的节流入口：每个终端最多每 {@value #HEAL_INTERVAL_TICKS} 拍（30 秒）自愈一次。
     * <p><b>为什么要节流</b>：{@code healUnitCandidateTags()} 逐步骤用配方 id 反查配方，
     * 「起步原料候选」那一支还要遍历<b>全部</b>序列装配配方并逐条展开标签候选 ——
     * 只要该终端的起步原料本来就只有一个候选（永远补不出 ≥2 件），它<b>每次调用都会重跑整轮扫描</b>
     * （javadoc 里说的「幂等、反复调用零开销」在这种情况下并不成立）。这正是「与网络规模无关、
     * 但每次开界面都要白付一次」的固定开销。</p>
     */
    private static void healIfDue(final SequencePatternTerminalBlockEntity terminal) {
        final net.minecraft.world.level.Level level = terminal.getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        final long now = level.getGameTime();
        final Long last = HEAL_STAMP.get(terminal);
        if (last != null && now - last >= 0L && now - last < HEAL_INTERVAL_TICKS) {
            return; // 距上次自愈还不到 30 秒：跳过（自愈只对「修复前生成的老样板」有意义）
        }
        HEAL_STAMP.put(terminal, now);
        terminal.healUnitCandidateTags();
    }
}
