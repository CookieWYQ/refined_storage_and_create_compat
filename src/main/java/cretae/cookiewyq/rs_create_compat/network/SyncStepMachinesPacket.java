package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
import cretae.cookiewyq.rs_create_compat.data.SequencePatternData;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

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

    /** 客户端最近一次收到的步骤机器快照（可能为空列表）。 */
    public static List<Entry> getLastReceived() {
        return lastReceived;
    }

    /** 从快照里取某一步的条目（没有则返回 null）。 */
    public static Entry stepAt(final int stepIndex) {
        for (final Entry entry : lastReceived) {
            if (entry.stepIndex() == stepIndex) {
                return entry;
            }
        }
        return null;
    }

    /** 由终端方块实体构造全量快照（0 .. arrangementSize-1）。 */
    public static SyncStepMachinesPacket from(final SequencePatternTerminalBlockEntity terminal) {
        if (terminal == null) {
            return new SyncStepMachinesPacket(List.of());
        }
        // 先把「每一步是否已有相同单元样板」的扫描缓存刷新到最新，再据此下发（客户端只读展示）
        terminal.refreshStepDuplicateCache();
        // **老样板自愈**（2026-10-05）：修复之前导入 / 生成的样板 NBT 里没有 InputCandidates，
        // 界面因此永远只显示代表物（用户实测：「铁粒还是只显示个铁粒」）。
        // 界面一打开就按配方把候选组补算写回 —— 幂等（已有候选的步直接跳过），因此不必让玩家重新导入。
        terminal.healUnitCandidateTags();
        final HolderLookup.Provider registries = terminal.getLevel() != null
            ? terminal.getLevel().registryAccess() : net.minecraft.core.RegistryAccess.EMPTY;
        final List<Entry> entries = new ArrayList<>(terminal.arrangementSize);
        for (int i = 0; i < terminal.arrangementSize; i++) {
            // 走方块实体的唯一读入口：导入后是展示数据，老存档是真实编排容器（见 arrangementView()）
            final ItemStack unit = terminal.arrangementUnit(i);
            final String recipeType = SequencePatternData.readUnitNew(unit, registries).recipeType();
            entries.add(new Entry(i,
                terminal.getStepMachinePos(i) != null,
                terminal.getStepMachineName(i),
                recipeType == null ? "" : recipeType,
                terminal.stepDuplicateExists(i),
                terminal.getStepSkipDuplicate(i),
                // 行内「已有 / 没有」= 该步的机器 / 样板是否已就位（链级；与判重是两个不同的问题，
                // 见 SequencePatternTerminalBlockEntity#stepUnitEquipped 的 javadoc）
                terminal.stepUnitEquipped(i)));
        }
        return new SyncStepMachinesPacket(entries);
    }

    public static void handle(final SyncStepMachinesPacket packet,
                              final net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() -> lastReceived = List.copyOf(packet.steps()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
