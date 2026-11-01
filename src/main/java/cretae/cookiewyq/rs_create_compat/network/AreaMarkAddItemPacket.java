package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.CollectionCacheBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.Area;
import cretae.cookiewyq.rs_create_compat.support.AreaMarks;
import cretae.cookiewyq.rs_create_compat.support.AreaSelection;
import cretae.cookiewyq.rs_create_compat.support.AreaSelectorTargets;
import cretae.cookiewyq.rs_create_compat.support.MarkerEntry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * C2S：<b>把「一个物品 id + 数据组件补丁」直接加进某个区域的 ghost 标记表</b>（第 125 轮）。
 *
 * <h2>为什么必须再开一个包（而不是复用 {@link AreaMarkEditPacket}）</h2>
 * <p>用户第 2 条实机反馈：「这个界面首先丑，其次呢<b>没有 JEI</b>」。他骂的不是 JEI 面板挡住界面，
 * 而是<b>标记界面里没法从 JEI 拖物品</b>：机器路径的「编辑过滤」打开的是
 * {@code AreaMarkScreen}，而 JEI 的 ghost handler 只给归流缓存仓主界面的 48 格注册过 ——
 * 标记界面完全没有，玩家只能翻背包。</p>
 * <p>但把 JEI 拖入接到既有通道上有个硬障碍：{@link AreaMarkEditPacket#ACTION_ADD} 的第 5 个参数是
 * <b>背包格下标</b>（服务端 {@code player.getInventory().getItem(slot)} 去背包取物品），
 * 而 JEI 拖出来的是<b>配方幽灵 ItemStack</b>（多数情况下玩家背包里根本没有那件物品）
 * ⇒ 服务端取不到东西、静默丢包。同理 {@link SetCollectionMarkerConfigPacket} 也救不了：
 * 它在高级仓里走 {@code AdvancedCollectionCacheMenu#applyMarkerConfig}，
 * 那条路只<b>更新已有条目</b>（{@code setAreaMarkerConfig} 找不到条目就返回 false），
 * 新 id 被直接忽略。</p>
 * <p>因此本包<b>只做一件既有通道做不到的事</b>：把「物品类型 + 组件补丁」本身带过网络，
 * 由服务端在自己的注册表上下文里还原成 {@link ItemStack} 再写进标记表。
 * 它与 {@link AreaMarkEditPacket} 的落点、维度闸门、去重、上限、体积预算<b>逐条同源</b>
 * （两条写通道必须对同一份 {@link AreaMarks} 说同一套规则，否则「界面能加、服务端不认」）。</p>
 *
 * <h2>安全边界（为什么不能只信客户端）</h2>
 * <ul>
 *     <li>落点照样走 {@link AreaSelectorTargets#resolve}：菜单路径必须是<b>玩家当前打开</b>的、
 *     {@code containerId} 一致且 {@code stillValid} 的高级仓菜单槽；{@code containerId < 0} 只认主手；</li>
 *     <li>区域必须<b>真的在选定器上</b>（按六坐标几何匹配）⇒ 防伪造坐标往物品里塞条目；</li>
 *     <li>维度闸门与区域包一致：选定器已绑定别的维度 ⇒ 丢包；</li>
 *     <li>单条体积（{@link AreaMarks#isOversized}）与<b>整件选定器</b>的整表预算
 *     （{@link AreaMarks#isOverBudget}）都在服务端再判一次：客户端可以改，服务端不信；</li>
 *     <li>条数上限 {@link CollectionCacheBlockEntity#MATCH_CAPACITY}、同 id 不重复。</li>
 * </ul>
 *
 * @param containerId 菜单 id（{@code -1} = 主手路径）
 * @param area        目标区域（六坐标几何；名字不参与匹配）
 * @param itemId      物品 / 流体注册名（{@code fluid = true} 时是流体 id）
 * @param patch       物品的数据组件补丁（{@link MarkerEntry#encodeComponents} 的产物；可为空）
 * @param count       拖入堆叠数量（物品语义上只作参考，标记门槛恒为
 *                    {@link CollectionCacheBlockEntity#MIN_UI_MARK_AMOUNT}；流体 = mB）
 * @param fluid       是不是流体 / 气体
 */
public record AreaMarkAddItemPacket(int containerId,
                                    Area area,
                                    ResourceLocation itemId,
                                    CompoundTag patch,
                                    int count,
                                    boolean fluid) implements CustomPacketPayload {

    public static final Type<AreaMarkAddItemPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(
            RS_Create_Compat.MODID, "area_mark_add_item"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AreaMarkAddItemPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AreaMarkAddItemPacket::containerId,
            Area.STREAM_CODEC, AreaMarkAddItemPacket::area,
            ResourceLocation.STREAM_CODEC, AreaMarkAddItemPacket::itemId,
            ByteBufCodecs.COMPOUND_TAG, AreaMarkAddItemPacket::patch,
            ByteBufCodecs.VAR_INT, AreaMarkAddItemPacket::count,
            ByteBufCodecs.BOOL, AreaMarkAddItemPacket::fluid,
            AreaMarkAddItemPacket::new
        );

    public AreaMarkAddItemPacket {
        // 网络边界归一化：null 一律收敛成「空」，绝不让下游多一层判空
        patch = patch == null ? new CompoundTag() : patch;
        count = Math.max(0, Math.min(64, count));
    }

    public static void handle(final AreaMarkAddItemPacket packet, final IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) {
            return;
        }
        handle(packet, player);
    }

    public static void handle(final AreaMarkAddItemPacket packet, final ServerPlayer player) {
        if (player == null || player.isSpectator()) {
            return; // 旁观者能发包，但不该改任何物品数据
        }
        final ItemStack target = AreaSelectorTargets.resolve(packet.containerId(), player);
        if (target.isEmpty()) {
            return;
        }
        final AreaSelection selection = AreaSelection.get(target);
        // 维度闸门：与 AreaSelectorEditPacket / AreaMarkEditPacket 同一条口径
        final ResourceLocation dimension = player.level().dimension().location();
        if (selection.dimension() != null && !selection.dimension().equals(dimension)) {
            return;
        }
        final Area area = findArea(selection, packet.area());
        if (area == null) {
            return; // 区域不在选定器上（伪造坐标 / 陈旧包）：丢包
        }
        final List<MarkerEntry> entries = AreaMarks.get(target, area, player.registryAccess());
        final MarkerEntry candidate = candidateOf(packet, player);
        if (candidate == null || candidate.isEmpty()) {
            return; // 未知 id / 补丁解不出来 / 条目太大：宁可丢包，绝不写半条坏数据
        }
        if (entries.size() >= CollectionCacheBlockEntity.MATCH_CAPACITY) {
            return;
        }
        for (final MarkerEntry entry : entries) {
            // 去重口径与 AreaMarkEditPacket#ACTION_ADD 逐字一致：同类别 + 同 id
            if (entry != null && !entry.isEmpty() && entry.fluid() == candidate.fluid()
                && entry.id() != null && entry.id().equals(candidate.id())) {
                return;
            }
        }
        if (AreaMarks.isOversized(candidate)) {
            return;
        }
        final List<MarkerEntry> next = new ArrayList<>(entries.size() + 1);
        next.addAll(entries);
        next.add(candidate);
        if (AreaMarks.isOverBudget(target, area, next)) {
            return;
        }
        AreaMarks.put(target, area, next);
        // 与另两条写通道同一套同步口径：通知菜单 / 背包 + 让方块实体标脏 + 让区域快照失效。
        // 快照失效后 {@code broadcastChanges()} 会在下一 tick 补发一份新的 AdvancedAreaSnapshotPacket，
        // 侧栏的标记网格因此自动刷新 —— 不需要任何「手动刷新」通道（契约 §4.5）。
        AreaSelectorTargets.sync(player, packet.containerId());
    }

    /**
     * 由「id + 组件补丁」还原一条 {@link MarkerEntry}（服务端注册表上下文）。
     * <p>物品走 {@link MarkerEntry#item}（与机器 48 格窗口 / 标记界面同一个构造器 ⇒ 组件补丁的
     * 编码口径完全一致）；流体 / 气体走 {@link MarkerEntry#fluidEntry}（世界流体方块没有数据组件，
     * 与 {@code MarkerEntry.fluidEntry} 的既有语义一致）。</p>
     *
     * @return {@code null} = 这条不值得写（未知 id / 补丁超预算 / 解不出来）
     */
    @Nullable
    private static MarkerEntry candidateOf(final AreaMarkAddItemPacket packet,
                                           final ServerPlayer player) {
        if (packet.itemId() == null) {
            return null;
        }
        if (packet.fluid()) {
            if (!BuiltInRegistries.FLUID.containsKey(packet.itemId())) {
                return null; // 未知流体 id：不写（否则区域快照会把一条假条目标出来）
            }
            return MarkerEntry.fluidEntry(packet.itemId(),
                Math.max(1L, packet.count()), false);
        }
        final Item item = BuiltInRegistries.ITEM.get(packet.itemId());
        if (item == Items.AIR && !packet.itemId().equals(BuiltInRegistries.ITEM.getKey(Items.AIR))) {
            return null; // 未知物品 id（含 "minecraft:air" 以外的空格）
        }
        final ItemStack stack = new ItemStack(item, Math.max(1, packet.count()));
        // 补丁体积的<b>权威</b>判据是随后的 AreaMarks.isOversized(candidate)（与另两条写通道同一个预算），
        // 这里只负责解码：解码失败按空补丁处理（MarkerEntry.decodeComponents 绝不抛异常）。
        final var components = MarkerEntry.decodeComponents(packet.patch(), player.registryAccess());
        if (!components.isEmpty()) {
            stack.applyComponents(components);
        }
        return MarkerEntry.item(stack, CollectionCacheBlockEntity.MIN_UI_MARK_AMOUNT, false,
            player.registryAccess());
    }

    /**
     * 在选定器上按<b>六坐标几何</b>找这个区域（返回物品上的那个实例，名字以物品上的为准）。
     * <p>与 {@code AreaMarkEditPacket#findArea} 同一口径（那边是 private，这里只能照抄一份 ——
     * 两处都只有 8 行，且各自的注释都指回对方；合并成公共工具反而要把「防伪造坐标」的语义
     * 从两个写通道里抽出来，收益不抵风险）。</p>
     *
     * @return {@code null} = 伪造 / 陈旧包
     */
    @Nullable
    private static Area findArea(@Nullable final AreaSelection selection,
                                 @Nullable final Area candidate) {
        if (selection == null || candidate == null) {
            return null;
        }
        final int index = selection.indexOfGeometry(candidate, -1);
        return index < 0 || index >= selection.areaCount() ? null : selection.areas().get(index);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
