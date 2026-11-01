package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.CollectionCacheBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.Area;
import cretae.cookiewyq.rs_create_compat.support.AreaMarks;
import cretae.cookiewyq.rs_create_compat.support.AreaSelection;
import cretae.cookiewyq.rs_create_compat.support.AreaSelectorTargets;
import cretae.cookiewyq.rs_create_compat.support.MarkerEntry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * C2S：<b>给「某一个区域」配 ghost 过滤标记</b>（第 112 轮，用户原话：「无法正常的单独给某一个区域标记，
 * 就非得把这个标记放在方块中进行吗这样子感觉很麻烦，切换的时候更麻烦」）。
 *
 * <h2>为什么要新开一个包（而不是复用 {@link AreaSelectorEditPacket}）</h2>
 * <p>区域包写的是「区域几何 / 名字 / 选中」这些<b>低熵</b>数据（几个 int + 一个短字符串）；
 * 过滤标记是<b>变长、带数据组件补丁</b>的（{@link MarkerEntry} 自带 {@code CompoundTag}），
 * 塞进旧包要么改旧包的网络契约（旧客户端 / 旧存档兼容性风险），要么给每条标记都开一个包。
 * 这里只传「<b>要加哪个背包格</b>」和「删第几条」这类<b>意图</b>，真正的标记由服务端从
 * 玩家自己的背包里读出来构造 —— 客户端<b>不能自带任意 NBT</b>，但可以<b>选择</b>自己背包里
 * 本身带大 NBT 的物品（成书 / 带内容物的容器），所以单条标记的编码大小另有上限，见
 * {@link AreaMarks#isOversized}。</p>
 *
 * <h2>落点与校验（与 {@link AreaSelectorEditPacket} 同源）</h2>
 * <ul>
 *     <li>目标物品：{@link AreaSelectorTargets#resolve}（手持 / 菜单槽，其余丢包）；</li>
 *     <li>旁观者拒绝；维度闸门与区域包一致（已绑定别的维度 ⇒ 丢包）；</li>
 *     <li>区域必须<b>真的在选定器上</b>（按六坐标几何匹配），否则丢包（防伪造坐标往物品里塞条目）；</li>
 *     <li>{@link #ACTION_ADD}：背包格下标合法、那一格非空、条数未到上限、同 id 不重复、
 *     单条编码不超过 {@link AreaMarks#MAX_MARKER_BYTES}；
 *     <b>不消耗物品</b>（ghost 标记，与机器里 Shift + 左键同一语义）；</li>
 *     <li>{@link #ACTION_REMOVE}：下标合法；{@link #ACTION_CLEAR}：直接清空该区域（= 该区域什么都不吸）。</li>
 * </ul>
 *
 * <h2>与「机器里的 48 格 ghost 窗口」的关系</h2>
 * <p>两者写的是<b>同一份数据</b>（{@link AreaMarks}，存在选定器物品上）：机器窗口适合细调
 * 数量门槛 / NBT / 标签，本包 + {@code AreaMarkScreen} 适合「拿着选定器随处配」。</p>
 * <p><b>刷新口径不完全相同（如实说明）</b>：本包的每条写入口写完后走
 * {@link AreaSelectorTargets#sync}；机器侧 48 格窗口走
 * {@code AdvancedCollectionCacheBlockEntity#writeMarks}（{@code invalidateAreaSnapshot} +
 * {@code setChanged} + 菜单广播），<b>不</b>走 {@code sync}。两条路径保证的是「同一份数据」，
 * 而不是「同一个同步函数」。</p>
 *
 * <h2>{@link #ACTION_REMOVE} 的下标契约（第 119 轮 C-5 / 第 120 轮）</h2>
 * <p>REMOVE 是<b>按下标</b>删的，而这里的下标由客户端给出。因此它成立的前提是
 * <b>客户端与服务端用同一套解码口径</b>读同一个 {@code AreaMarks} 表 —— 也就是两边都必须带注册表
 * （{@code player.registryAccess()} / 客户端 {@code level.registryAccess()}）。
 * 旧档里 {@code Item}（整份 ItemStack）编码的条目在 {@code registries == null} 时会被
 * {@code CollectionCacheBlockEntity#decodeMarkerTag} 当成「读不出来」丢弃；客户端以前用两参
 * {@code AreaMarks.get(stack, area)}（= {@code registries = null}）读，得到的列表<b>更短 / 顺序不同</b>，
 * 于是「点第 N 格」删掉的是服务端列表的另一条。现在客户端统一走
 * {@code AreaMarkScreen#clientMarks(...)}（带注册表，拿不到时显示空表而不是回退）。</p>
 * <p><b>没有改成「下标 + 条目指纹」的决策</b>：那需要给本包加字段 ⇒ 改网络契约的线格式
 * （旧客户端连新服务端会错位），代价高于收益；根因（两边解码口径不同）已经消掉，
 * 指纹校验留作后续加固建议（见 {@code docs/ROUND120_AREA_MARK_REGISTRY.md}）。</p>
 */
public record AreaMarkEditPacket(int containerId, int action, Area area, int markerIndex,
                                 int inventorySlot) implements CustomPacketPayload {
    /** 从背包第 {@code inventorySlot} 格取物品，往 {@code area} 加一条标记（不消耗物品）。 */
    public static final int ACTION_ADD = 0;
    /** 删 {@code area} 的第 {@code markerIndex} 条标记。 */
    public static final int ACTION_REMOVE = 1;
    /** 清空 {@code area} 的全部标记（该区域从此什么都不吸）。 */
    public static final int ACTION_CLEAR = 2;

    /** ADD / CLEAR 时不需要标记下标；REMOVE 时不需要背包格。统一用一个占用位填 0。 */
    public static final int NO_INDEX = -1;

    public static final Type<AreaMarkEditPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "area_mark_edit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AreaMarkEditPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AreaMarkEditPacket::containerId,
            ByteBufCodecs.VAR_INT, AreaMarkEditPacket::action,
            Area.STREAM_CODEC, AreaMarkEditPacket::area,
            ByteBufCodecs.VAR_INT, AreaMarkEditPacket::markerIndex,
            ByteBufCodecs.VAR_INT, AreaMarkEditPacket::inventorySlot,
            AreaMarkEditPacket::new
        );

    public static void handle(final AreaMarkEditPacket packet, final IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) {
            return;
        }
        handle(packet, player);
    }

    public static void handle(final AreaMarkEditPacket packet, final ServerPlayer player) {
        if (player.isSpectator()) {
            return; // 旁观者能发包，但不该改任何物品数据
        }
        final ItemStack target = AreaSelectorTargets.resolve(packet.containerId(), player);
        if (target.isEmpty()) {
            return;
        }
        AreaSelection selection = AreaSelection.get(target);
        final ResourceLocation dimension = player.level().dimension().location();
        // 维度闸门：与区域包同一条口径（标记也是区域数据；跨维度改写会让「另一台机器」看到陌生标记）
        if (selection.dimension() != null && !selection.dimension().equals(dimension)) {
            return;
        }
        final Area area = findArea(selection, packet.area());
        if (area == null) {
            return; // 区域不在选定器上（伪造坐标 / 陈旧包）：丢包
        }
        switch (packet.action()) {
            case ACTION_ADD -> {
                final List<MarkerEntry> entries = AreaMarks.get(target, area, player.registryAccess());
                if (entries.size() >= CollectionCacheBlockEntity.MATCH_CAPACITY) {
                    return;
                }
                final ItemStack source = inventoryStack(player, packet.inventorySlot());
                if (source.isEmpty()) {
                    return;
                }
                final ResourceLocation id =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(source.getItem());
                if (id == null) {
                    return;
                }
                for (final MarkerEntry entry : entries) {
                    if (entry != null && !entry.isEmpty() && !entry.fluid() && id.equals(entry.id())) {
                        return; // 已标记过（与机器窗口的「该物品已在匹配区标记过」同一语义）
                    }
                }
                final MarkerEntry candidate = MarkerEntry.item(source,
                    CollectionCacheBlockEntity.MIN_UI_MARK_AMOUNT, false, player.registryAccess());
                if (AreaMarks.isOversized(candidate)) {
                    // 单条标记的组件补丁太大（成书 / 带内容物的容器 / 大 NBT 物品）：写进去会让选定器的
                    // CUSTOM_DATA 随物品网络同步时撑爆包。客户端已先拦并提示（notice.too_complex），这里是服务端兜底。
                    return;
                }
                final List<MarkerEntry> next = new ArrayList<>(entries);
                next.add(candidate);
                if (AreaMarks.isOverBudget(target, area, next)) {
                    // 第 114 轮：单条没超（上面那条），但整件选定器的标记表连同这一条要超总量上限。
                    // 客户端 AreaMarkScreen 会先拦并提示；这里是服务端兜底（防改包 / 防客户端版本漂移）。
                    return;
                }
                AreaMarks.put(target, area, next);
            }
            case ACTION_REMOVE -> {
                final List<MarkerEntry> entries = AreaMarks.get(target, area, player.registryAccess());
                final int index = packet.markerIndex();
                if (index < 0 || index >= entries.size()) {
                    return;
                }
                final List<MarkerEntry> next = new ArrayList<>(entries);
                next.remove(index);
                AreaMarks.put(target, area, next);
            }
            case ACTION_CLEAR -> AreaMarks.remove(target, area);
            default -> {
                return;
            }
        }
        AreaSelectorTargets.sync(player, packet.containerId());
    }

    /**
     * 在选定器上按<b>六坐标几何</b>找这个区域（返回物品上的那个实例，名字以物品上的为准）。
     * <p>找不到 ⇒ {@code null}（= 伪造 / 陈旧包）。名字不参与匹配：改名后的区域仍然接受标记编辑。</p>
     */
    @Nullable
    private static Area findArea(@Nullable final AreaSelection selection, @Nullable final Area candidate) {
        if (selection == null || candidate == null) {
            return null;
        }
        final int index = selection.indexOfGeometry(candidate, -1);
        return index < 0 || index >= selection.areaCount() ? null : selection.areas().get(index);
    }

    /** 玩家背包第 {@code slot} 格的物品（越界返回空 —— 防伪造下标）。 */
    private static ItemStack inventoryStack(final ServerPlayer player, final int slot) {
        if (slot < 0 || slot >= player.getInventory().getContainerSize()) {
            return ItemStack.EMPTY;
        }
        return player.getInventory().getItem(slot);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
