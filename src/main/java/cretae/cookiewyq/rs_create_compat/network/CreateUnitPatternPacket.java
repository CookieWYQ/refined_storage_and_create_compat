package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
import cretae.cookiewyq.rs_create_compat.data.SequencePatternData;
import cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu;
import cretae.cookiewyq.rs_create_compat.support.UnitPatternDedupe;
import cretae.cookiewyq.rs_create_compat.support.UnitPatternDedupePolicy;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * C2S：新语义（v4）—— 按「单元样板三项基础信息」创建/覆盖一张单元样板。
 * <p>三项：{@link #requiresInput()}（是否需要输入原料）、{@link #recipeType()}（配方类型 id，如
 * {@code create:pressing}）、{@link #name()}（用户自定义名）；{@link #input()} 为可选的输入标记物品。</p>
 * <p>写入目标 {@link #targetSlot()}：{@code -1} = 写入当前终端菜单的单元样板输出槽；
 * {@code >= 0} = 写入终端单元样板库的该下标（越界自动收敛到末格）。</p>
 * <p>说明：新语义下单元样板不再绑定具体机器，因此旧 {@code machine}/{@code crafter} 分量写空串；
 * 每一步的机器在导出装配样板时按 recipeType 自动指派（见终端方块实体）。</p>
 *
 * <h2>附带能力：生成时自动查重</h2>
 * <p>本包在<b>新建</b>单元样板时顺手做一次「语义重复」检查（{@link UnitPatternDedupe}）——
 * 档位真值来自世界存档（{@code UnitPatternDedupePolicy}，缺省 = 开），<b>服务端权威</b>。
 * 该档位不再有界面入口：管理舱里的全局开关已按用户要求删除（它与序列装配终端的
 * <b>「每一步一个跳过重复」</b>开关重复），因此这里只把它当作<b>内部默认来源</b>读取；
 * 旧存档里存的 {@code false} 仍会被读到（不改变既有判定）。</p>
 * <p><b>查重时机</b>：只在「<b>新建</b>」时查（{@code TARGET_OUTPUT_SLOT} / {@code TARGET_FIRST_EMPTY_LIBRARY}）；
 * {@code targetSlot >= 0} 是「编辑写回原格」的覆盖写入，若也参与查重，玩家改个名字就会被自己那一格判成重复
 * 而写不回去 —— 因此覆盖路径一律不查重。</p>
 */
public record CreateUnitPatternPacket(String recipeType, String name, boolean requiresInput,
                                      ItemStack input, int targetSlot) implements CustomPacketPayload {
    /** targetSlot 取值：写入当前菜单的单元样板输出槽。 */
    public static final int TARGET_OUTPUT_SLOT = -1;
    /** targetSlot 取值：写入终端单元样板库的第一个空位（库满时自动扩容追加，不覆盖已有样板）。 */
    public static final int TARGET_FIRST_EMPTY_LIBRARY = -2;

    public static final Type<CreateUnitPatternPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "create_unit_pattern"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CreateUnitPatternPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, CreateUnitPatternPacket::recipeType,
            ByteBufCodecs.STRING_UTF8, CreateUnitPatternPacket::name,
            ByteBufCodecs.BOOL, CreateUnitPatternPacket::requiresInput,
            ItemStack.OPTIONAL_STREAM_CODEC, CreateUnitPatternPacket::input,
            ByteBufCodecs.VAR_INT, CreateUnitPatternPacket::targetSlot,
            CreateUnitPatternPacket::new
        );

    public static void handle(final CreateUnitPatternPacket packet, final ServerPlayer player) {
        if (!(player.containerMenu instanceof SequencePatternTerminalMenu menu)) {
            return;
        }
        final SequencePatternTerminalBlockEntity terminal = menu.getTerminal();
        if (terminal == null || terminal.getLevel() == null) {
            return;
        }
        final HolderLookup.Provider registries = terminal.getLevel().registryAccess();
        final String recipeType = packet.recipeType() == null ? "" : packet.recipeType();
        final ItemStack input = packet.input() == null ? ItemStack.EMPTY : packet.input().copy();
        final ItemStack unit = new ItemStack(RS_Create_Compat.SEQUENCE_UNIT_PATTERN.get());
        SequencePatternData.writeUnit(unit, new SequencePatternData.UnitData(
            "", input, "", "", -1,
            packet.requiresInput(),
            packet.name() == null ? "" : packet.name(),
            recipeType), registries);

        // 生成（新建）时的「自动跳过重复样板」：服务端权威 —— 开关真值取自世界存档，
        // 命中判据与扫描范围全部在 UnitPatternDedupe（只读扫描：不复制、不销毁任何样板）。
        final boolean creating = packet.targetSlot() == TARGET_OUTPUT_SLOT
            || packet.targetSlot() == TARGET_FIRST_EMPTY_LIBRARY;
        if (creating && UnitPatternDedupePolicy.skipDuplicates(terminal.getLevel())) {
            final UnitPatternDedupe.Match duplicate = UnitPatternDedupe.findDuplicate(
                UnitPatternDedupe.networkOf(terminal), unit, registries);
            // 本轮新增：把「跟哪一张判成了重复」落日志（审计 §7.3 的取证缺口 —— 命中来源此前当场丢弃）。
            // 玩家看到的聊天提示只有「来源 + 槽位」，日志里还多一层判据摘要（配方 / 步序 / 操作类型 /
            // 代表物 / 候选组），事后可核对是哪一个分量让这次新建被判重。
            cretae.cookiewyq.rs_create_compat.support.RsccAssemblyDebug.dedupe(
                "unit create", -1, duplicate != null,
                UnitPatternDedupe.describe(duplicate), duplicate == null ? -1 : duplicate.slot(),
                UnitPatternDedupe.basisOf(unit, registries));
            if (duplicate != null) {
                // 明确告诉玩家「已存在完全相同的那一张、在哪里」——而不是静默什么都不做。
                player.displayClientMessage(Component.translatable(
                    "message.rs_create_compat.unit_pattern_dedupe.skipped",
                    duplicate.source(), duplicate.slot()), true);
                return;
            }
        }

        if (packet.targetSlot() == TARGET_OUTPUT_SLOT) {
            terminal.unitOutputSlot.setStackInSlot(0, unit);
        } else {
            // 负值哨兵（首空位）在服务端换算成真实下标；正值直接按库下标写入。
            // 库无上限：越界写入会自动扩容（不再收敛到末格，避免覆盖已有样板）。
            // 网络边界防护：最多允许写到「末尾空槽」位置（即扩容一格），避免恶意包用超大下标撑爆库。
            final int requested = packet.targetSlot() == TARGET_FIRST_EMPTY_LIBRARY
                ? firstEmptyLibrarySlot(terminal) : Math.max(0, packet.targetSlot());
            final int slot = Math.min(requested, terminal.unitLibrary.getSlots());
            terminal.unitLibrary.setStackInSlot(slot, unit);
        }
        terminal.setChanged();
    }

    /** 单元样板库的第一个空位下标（库满时返回末尾，写入即自动扩容追加，不会覆盖已占用格）。 */
    private static int firstEmptyLibrarySlot(final SequencePatternTerminalBlockEntity terminal) {
        return terminal.unitLibrary.firstEmptySlot();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
