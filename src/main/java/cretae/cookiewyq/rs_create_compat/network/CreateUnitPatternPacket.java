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
 * {@code >= 0} = 写入终端单元样板库的该下标。<b>第 117 轮更正</b>：越界<b>不是</b>「收敛到末格」——
 * 第 116 轮起 {@code >= max(当前容量, 1024)} 一律拒绝并提示（见 {@code warnLibraryFull}），
 * 而两个哨兵以外的负下标也一并拒绝（旧注释写的「收敛」与实现不符，复核 L2 报出）。</p>
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
    /**
     * targetSlot 取值：写入终端单元样板库的第一个空位（<b>第 117 轮更正</b>：不再是「库满时自动扩容
     * 追加」——第 116 轮起库有 1024 格硬上限，到上限后本包拒绝写入并提示，原有样板一张不动）。
     */
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
        final ItemStack input = packet.input() == null ? ItemStack.EMPTY : packet.input().copy();
        // 第 116 轮：三项全部是客户端给的、都会写进单元样板的 CustomData 并永久落盘
        //（物品带全部数据组件、名字与配方类型是任意长字符串）。这里按「同一把尺」先判后写：
        // 超限就整包拒绝（不生成、不落盘、不动任何已有样板）+ 日志留证 + 动作栏告知。
        if (cretae.cookiewyq.rs_create_compat.support.MarkerBudget.isStackTooBig(input, registries)
            || tooLong(packet.name()) || tooLong(packet.recipeType())) {
            rejectTooLarge(player);
            return;
        }
        final String recipeType = packet.recipeType() == null ? "" : packet.recipeType();
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
            // 第 117 轮（复核 L2）：输出槽里可能已经有一张样板（界面允许连续生成 / 玩家先放一张在那）。
            // 旧实现直接 setStackInSlot(0, unit) ⇒ 被顶掉的那张样板（是物品）凭空消失。
            // 按本工程「绝不销毁玩家物品」的规矩：先把旧样板交给玩家（背包放不下就掉在脚下），再写入。
            final ItemStack previous = terminal.unitOutputSlot.getStackInSlot(0);
            if (!previous.isEmpty()) {
                final ItemStack refund = previous.copy();
                terminal.unitOutputSlot.setStackInSlot(0, ItemStack.EMPTY);
                if (!player.getInventory().add(refund)) {
                    player.drop(refund, false);
                }
            }
            terminal.unitOutputSlot.setStackInSlot(0, unit);
        } else {
            // 负值哨兵（首空位）在服务端换算成真实下标；正值直接按库下标写入。
            // 库可自动扩容，但第 116 轮起有<b>容量硬上限</b>（{@code MAX_UNIT_LIBRARY_SLOTS}）：
            // 到上限后一律拒绝新增写入（原有样板一张不动），绝不越界扩容 / 覆盖末格。
            // 上限按「max(当前容量, 硬上限)」算：旧存档里已经超过上限的库，仍允许玩家编辑它已有的格。
            if (packet.targetSlot() < 0 && packet.targetSlot() != TARGET_FIRST_EMPTY_LIBRARY) {
                // 第 117 轮（复核 L2）：除了两个哨兵之外的负下标一律非法（改包可达）。
                // 旧实现的 Math.max(0, ·) 会把它写到 0 号格、覆盖一张已有样板 —— 静默做错事。
                // 合法界面不会发出这种请求，所以这里只留日志（每进程一条），不给玩家造一条假提示。
                warnNegativeTarget();
                return;
            }
            final int writableLimit = Math.max(terminal.unitLibrary.getSlots(),
                SequencePatternTerminalBlockEntity.MAX_UNIT_LIBRARY_SLOTS);
            if (packet.targetSlot() >= writableLimit) {
                warnLibraryFull(player);
                return;
            }
            final int requested = packet.targetSlot() == TARGET_FIRST_EMPTY_LIBRARY
                ? firstEmptyLibrarySlot(terminal) : packet.targetSlot();
            if (requested < 0) {
                // firstEmptySlot() 返回 -1 = 库已满且到上限
                warnLibraryFull(player);
                return;
            }
            // 第 118 轮：格数够之外还有「合计体积预算」这道闸（见 support/UnitLibraryLimits）。
            // 两道闸各自独立：格数满 = firstEmptySlot 返回 -1 / 下标越界；体积满 = 下面这一条。
            if (!terminal.unitLibrary.canAcceptMore(unit)) {
                warnLibraryBytesFull(player);
                return;
            }
            final int slot = Math.min(requested, terminal.unitLibrary.getSlots());
            if (!terminal.unitLibrary.trySetStackInSlot(slot, unit)) {
                // 写入入口（唯一）自己的兜底判据：正常到不了这里（上面已判过），
                // 但万一撞上就按「库已满」提示，绝不静默吞掉玩家这次新建。
                warnLibraryBytesFull(player);
            }
        }
        terminal.setChanged();
    }

    /**
     * 非法负下标（改包可达）的 WARN：每进程一条。合法界面只会发 {@link #TARGET_OUTPUT_SLOT} 与
     * {@link #TARGET_FIRST_EMPTY_LIBRARY} 两个哨兵，所以这里不给玩家造提示（玩家永远看不到）。
     */
    private static boolean negativeTargetWarned;

    private static void warnNegativeTarget() {
        if (negativeTargetWarned) {
            return;
        }
        negativeTargetWarned = true;
        LOGGER.warn("[rs_create_compat] 序列终端：拒绝了一次非法 targetSlot（负下标，除两个哨兵外都非法）——"
            + "旧实现会用 Math.max(0, ·) 把它写到 0 号格覆盖已有样板；正常界面不会发出这种请求");
    }

    /** 是否超过字符串长度上限（第 116 轮：{@code readUtf} 以前只受包长限制，可写进 NBT 的内容无界）。 */
    private static boolean tooLong(final String value) {
        return value != null && value.length() > SequencePatternTerminalBlockEntity.MAX_IMPORT_STRING_LENGTH;
    }

    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger("rs_create_compat/CreateUnitPatternPacket");

    /** 「库里已到上限」的 WARN：每进程只打一条 + 给玩家一条动作栏提示（否则「点了没反应」）。 */
    private static boolean libraryFullWarned;

    private static void warnLibraryFull(final ServerPlayer player) {
        if (!libraryFullWarned) {
            libraryFullWarned = true;
            LOGGER.warn("[rs_create_compat] 序列终端：单元样板库已达容量上限 {} 格，拒绝新增单元样板"
                    + "（原有样板未改动）；请先取出一些单元样板",
                SequencePatternTerminalBlockEntity.MAX_UNIT_LIBRARY_SLOTS);
        }
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
            "gui.rs_create_compat.sequence_pattern_terminal.generate.library_full",
            SequencePatternTerminalBlockEntity.MAX_UNIT_LIBRARY_SLOTS), true);
    }

    /** 「库里已到<b>合计体积</b>上限」的 WARN（第 118 轮）：每进程只打一条 + 同样的动作栏提示。 */
    private static boolean libraryBytesFullWarned;

    private static void warnLibraryBytesFull(final ServerPlayer player) {
        if (!libraryBytesFullWarned) {
            libraryBytesFullWarned = true;
            LOGGER.warn("[rs_create_compat] 序列终端：单元样板库已达合计体积上限 ≈ {} KB 线上，拒绝新增单元样板"
                    + "（原有样板未改动）；请先取出一些单元样板",
                cretae.cookiewyq.rs_create_compat.support.UnitLibraryLimits.MAX_LIBRARY_WIRE_KB);
        }
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
            "gui.rs_create_compat.sequence_pattern_terminal.generate.library_full_bytes",
            cretae.cookiewyq.rs_create_compat.support.UnitLibraryLimits.MAX_LIBRARY_WIRE_KB), true);
    }

    /** 「数据过大已拒绝」的 WARN + 玩家动作栏提示：每进程只打一条日志。 */
    private static boolean tooLargeWarned;

    private static void rejectTooLarge(final ServerPlayer player) {
        if (!tooLargeWarned) {
            tooLargeWarned = true;
            LOGGER.warn("[rs_create_compat] 序列终端：拒绝创建了一张单元样板 —— 输入物品的数据体积或"
                + "名字 / 配方类型长度超过上限（单条阈值 {} 堆估算 ≈ 2 KB 线上意图、字符串 {} 字符）；正常界面操作不会触发",
                cretae.cookiewyq.rs_create_compat.support.MarkerBudget.MAX_ENTRY_BYTES,
                SequencePatternTerminalBlockEntity.MAX_IMPORT_STRING_LENGTH);
        }
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
            "message.rs_create_compat.sequence.marker_too_large"), true);
    }

    /**
     * 单元样板库的第一个空位下标（未到上限时库满返回末尾 = 写入即自动扩容追加；
     * 第 116 轮起<b>到上限后返回 -1</b> = 调用方必须拒绝写入）。
     */
    private static int firstEmptyLibrarySlot(final SequencePatternTerminalBlockEntity terminal) {
        return terminal.unitLibrary.firstEmptySlot();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
