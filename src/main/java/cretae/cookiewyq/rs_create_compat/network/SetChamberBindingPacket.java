package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S：新语义（v4）—— 玩家在某台序列执行仓界面上一次性提交「配方类型 + 显示名」。
 * <p>目标解析（服务端）：① 优先取玩家<b>当前打开的执行仓菜单</b>所对应的方块实体
 * （界面未收到过 S2C 同步包、拿不到坐标时也能正常提交，距离由菜单 stillValid 保证）；
 * ② 回退按 {@link #pos()} 解析，并做与菜单 {@code stillValid} 一致的 ≤64 格距离校验。</p>
 * <p><b>链指向不在本包里</b>：链方向 = 方块放置时的朝向（那支箭头），由
 * {@link SequenceExecutionChamberBlockEntity#chainHead()} 纯推导，玩家无法配置；
 * 因此这里只剩「谁持有链级共享的名字 / 配方类型」这一件事，且一律写到链首（成员自动转发）。</p>
 * <p><b>并发</b>：所有写入都发生在服务端主线程的 {@code enqueueWork} 里（见 {@code RS_Create_Compat} 注册），
 * 因此天然串行、以最后一次提交为准；每次提交后服务端都回发权威快照给提交者，
 * 并广播给链上每一台正在被查看的执行仓（{@link SequenceExecutionChamberBlockEntity#broadcastChainBinding()}），
 * 任何拒绝路径也回发一次权威值 —— 客户端界面永远是权威值，不会出现「看着改了、服务端没改」。</p>
 */
public record SetChamberBindingPacket(BlockPos pos, String recipeType, String name)
    implements CustomPacketPayload {
    /** 占位坐标「由服务端按当前打开的执行仓菜单解析目标」（客户端拿不到坐标时使用）。 */
    public static final BlockPos NO_POS = BlockPos.ZERO;
    public static final Type<SetChamberBindingPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_chamber_binding"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetChamberBindingPacket> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetChamberBindingPacket::pos,
            ByteBufCodecs.STRING_UTF8, SetChamberBindingPacket::recipeType,
            ByteBufCodecs.STRING_UTF8, SetChamberBindingPacket::name,
            SetChamberBindingPacket::new
        );

    public static void handle(final SetChamberBindingPacket packet, final ServerPlayer player) {
        SequenceExecutionChamberBlockEntity chamber = null;
        // ① 当前打开的执行仓菜单（最可靠：距离由菜单 stillValid 保证，且不依赖客户端坐标）
        if (player.containerMenu instanceof cretae.cookiewyq.rs_create_compat.menu.SequenceExecutionChamberMenu menu
            && menu.getChamber() != null) {
            chamber = menu.getChamber();
        }
        // ② 回退：按包内坐标解析（兼容非界面路径）
        if (chamber == null) {
            final ServerLevel level = player.serverLevel();
            if (!level.isLoaded(packet.pos())) {
                return;
            }
            if (!(level.getBlockEntity(packet.pos()) instanceof SequenceExecutionChamberBlockEntity byPos)) {
                return; // 该位置不是序列执行仓
            }
            // 距离校验（与菜单 stillValid 一致）：防止远程修改他人机器
            if (player.distanceToSqr(packet.pos().getX() + 0.5,
                packet.pos().getY() + 0.5, packet.pos().getZ() + 0.5) > 64.0) {
                return;
            }
            chamber = byPos;
        }
        commit(player, chamber, packet);
    }

    /** 服务端权威提交（服务端主线程内串行；拒绝路径一律回发权威值，客户端界面不会停留在假象上）。 */
    private static void commit(final ServerPlayer player, final SequenceExecutionChamberBlockEntity chamber,
                               final SetChamberBindingPacket packet) {
        // ① 名字唯一性：同一网络内不允许两条不同链同名（空名 = 回退坐标显示，不算冲突）
        if (chamber.isNameTakenInNetwork(packet.name())) {
            player.displayClientMessage(Component.translatable(
                "message.rs_create_compat.chamber_name_duplicate", packet.name()), false);
            echo(player, chamber);
            return;
        }
        // ② 配方类型锁定（服务端权威，链级判据）：链上任一台还有单元样板时不允许改配方类型 ——
        // 已放样板都是按旧类型生成的（acceptsUnit 要求类型完全相等），改类型会让它们全部失配。
        // 必须由玩家把样板全部取走后才允许改；名字不受影响，仍然照常写入。
        final String requestedType = packet.recipeType() == null ? "" : packet.recipeType();
        final String currentType = chamber.getRecipeType() == null ? "" : chamber.getRecipeType();
        if (chamber.chainHasAnyUnit() && !requestedType.equals(currentType)) {
            chamber.setChamberName(packet.name());
            player.displayClientMessage(Component.translatable(
                "message.rs_create_compat.chamber_recipe_locked"), false);
            announce(player, chamber);
            return;
        }
        // ③ 写入（本台是链成员时自动转发到链首，因此「任意一处修改 = 整链生效」）
        chamber.setRecipeType(packet.recipeType());
        chamber.setChamberName(packet.name());
        announce(player, chamber);
    }

    /**
     * 提交成功：把权威快照回发给提交者，并广播给<b>链上每一台</b>正在被查看的执行仓
     * （不留「这一台改了、另一台还是旧值」的窗口）。
     */
    private static void announce(final ServerPlayer player, final SequenceExecutionChamberBlockEntity chamber) {
        echo(player, chamber);
        chamber.broadcastChainBinding();
    }

    /** 回发本台权威快照（提交者视角；客户端只镜像缓存，据此回填界面）。 */
    private static void echo(final ServerPlayer player, final SequenceExecutionChamberBlockEntity chamber) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            SyncChamberBindingPacket.of(chamber));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
