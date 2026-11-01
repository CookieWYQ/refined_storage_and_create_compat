package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
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
 *
 * <p><b>{@code name} 的长度收口（第 121 轮 ①，与第 120 轮 F5 同源）</b>：本包原先直接把客户端给的
 * {@code name} 写进执行仓（{@link SequenceExecutionChamberBlockEntity#setChamberName(String)}），
 * 长度只受包体大小限制 —— 改包 / 故障客户端可以塞进几十 KB 的字符串，随 NBT 一起进存档，
 * 而且这条链上每台仓的 {@code SyncChamberBindingPacket} 都会把它再发一遍。
 * 现在服务端在落盘前一律按 {@link SequencePatternTerminalBlockEntity#MAX_IMPORT_STRING_LENGTH}
 * （= 256）<b>码点</b>截断（见 {@link #clampChamberName(String)}）：重名判据与两处写入用的是
 * 同一个截断后的值，因此「客户端看到的名字」=「服务端判重用的名字」=「落盘的名字」。</p>
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
        // 名字先收口再参与任何判定 / 写入（第 121 轮 ①）：
        // 三条外部写入路径（重名拒绝前的判据、配方锁定分支的写入、正常写入）此前都直接用
        // packet.name()，长度无界；现在统一用截断后的 name —— 判重与落盘一定是同一个值，
        // 不会出现「判重放行、落盘被截成另一个名字」的错位。
        final String name = clampChamberName(packet.name());
        // ① 名字唯一性：同一网络内不允许两条不同链同名（空名 = 回退坐标显示，不算冲突）
        if (chamber.isNameTakenInNetwork(name)) {
            player.displayClientMessage(Component.translatable(
                "message.rs_create_compat.chamber_name_duplicate", name), false);
            echo(player, chamber);
            return;
        }
        // ② 配方类型锁定（服务端权威，链级判据）：链上任一台还有单元样板时不允许改配方类型 ——
        // 已放样板都是按旧类型生成的（acceptsUnit 要求类型完全相等），改类型会让它们全部失配。
        // 必须由玩家把样板全部取走后才允许改；名字不受影响，仍然照常写入。
        final String requestedType = packet.recipeType() == null ? "" : packet.recipeType();
        final String currentType = chamber.getRecipeType() == null ? "" : chamber.getRecipeType();
        if (chamber.chainHasAnyUnit() && !requestedType.equals(currentType)) {
            chamber.setChamberName(name);
            player.displayClientMessage(Component.translatable(
                "message.rs_create_compat.chamber_recipe_locked"), false);
            announce(player, chamber);
            return;
        }
        // ③ 写入（本台是链成员时自动转发到链首，因此「任意一处修改 = 整链生效」）
        chamber.setRecipeType(packet.recipeType());
        chamber.setChamberName(name);
        announce(player, chamber);
    }

    /**
     * 把客户端提交的显示名夹到 {@link SequencePatternTerminalBlockEntity#MAX_IMPORT_STRING_LENGTH}
     * （= 256）个<b>码点</b>（第 121 轮 ①，口径与第 120 轮 F5 的 {@code clampStepMachineName} 一致）。
     *
     * <p><b>为什么必须夹在这里</b>：{@code chamberName} 的唯一外部写入点就是本包的
     * {@link #commit}（另一条 NBT 写入路径 {@code SequenceExecutionChamberBlockEntity#loadAdditional}
     * 只是读回已落盘的值；链自愈 / 拆链移交写的都是 {@code ownChamberName()} 的拷贝，本身有界）。
     * 因此在这一层收口 = 「落盘值有界」的唯一保证，不需要（也不能只靠）客户端自觉。</p>
     *
     * <p><b>为什么按码点截断</b>：直接 {@code substring(0, 256)} 可能把一对代理对切成半个字符
     * （例如「一个 ASCII 前缀 + 200 个 emoji」时第 256 个 char 正好是代理对的高位），
     * 截断结果写进 NBT / 再序列化发回客户端都会变成乱码或替换符。码点截断只会切在字符边界上，
     * 因此结果永远是一段合法的 UTF-16 前缀。{@code length() <= 256} 的快路径是安全的：
     * 码点数一定 ≤ char 数，不可能超过上限。</p>
     */
    static String clampChamberName(final String name) {
        if (name == null) {
            return "";
        }
        if (name.length() <= SequencePatternTerminalBlockEntity.MAX_IMPORT_STRING_LENGTH) {
            return name;
        }
        final int codePoints = name.codePointCount(0, name.length());
        return name.substring(0, name.offsetByCodePoints(0,
            Math.min(codePoints, SequencePatternTerminalBlockEntity.MAX_IMPORT_STRING_LENGTH)));
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
