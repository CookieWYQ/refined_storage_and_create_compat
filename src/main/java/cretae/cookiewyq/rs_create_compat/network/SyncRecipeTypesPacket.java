package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.data.RecipeTypeNames;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * S2C：把「配方类型候选列表」下发给客户端（供滚轮选择控件使用）。
 * <p><b>候选构造</b>（{@link #from(Collection)}）：枚举
 * {@link BuiltInRegistries#RECIPE_TYPE} 的全部 key —— 因此<b>既含原版配方类型，也含其它模组注册的</b>；
 * 排序规则为「当前网络内已有执行仓绑定的类型优先（按显示名排序），其余按 id 排序」。</p>
 * <p>客户端只镜像缓存最近一次结果（{@link #getLastReceived()}），不参与任何权威写入。</p>
 */
public record SyncRecipeTypesPacket(List<String> recipeTypes) implements CustomPacketPayload {
    public static final Type<SyncRecipeTypesPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "sync_recipe_types"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncRecipeTypesPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), SyncRecipeTypesPacket::recipeTypes,
            SyncRecipeTypesPacket::new
        );

    /** 客户端最近一次收到的候选列表（不可变；永不为 null）。 */
    private static volatile List<String> lastReceived = List.of();

    /** 客户端最近一次收到的候选列表（可能为空列表）。 */
    public static List<String> getLastReceived() {
        return lastReceived;
    }

    /**
     * 由「已绑定配方类型集合」构造候选：全部已注册配方类型，已绑定的排前面。
     *
     * @param boundRecipeTypes 当前网络内已有执行仓绑定的配方类型 id（可为 null / 空）
     */
    public static SyncRecipeTypesPacket from(final Collection<String> boundRecipeTypes) {
        final Set<String> bound = boundRecipeTypes == null ? Set.of() : new HashSet<>(boundRecipeTypes);
        final List<ResourceLocation> boundIds = new ArrayList<>();
        final List<ResourceLocation> otherIds = new ArrayList<>();
        for (final ResourceLocation id : BuiltInRegistries.RECIPE_TYPE.keySet()) {
            if (bound.contains(id.toString())) {
                boundIds.add(id);
            } else {
                otherIds.add(id);
            }
        }
        // 已绑定组：按显示名（无翻译则回退 id）排序；其余组：按 id 排序
        boundIds.sort(Comparator
            .comparing((ResourceLocation id) -> RecipeTypeNames.of(id.toString()), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ResourceLocation::toString));
        otherIds.sort(Comparator.comparing(ResourceLocation::toString));
        final List<String> out = new ArrayList<>(boundIds.size() + otherIds.size());
        for (final ResourceLocation id : boundIds) {
            out.add(id.toString());
        }
        for (final ResourceLocation id : otherIds) {
            out.add(id.toString());
        }
        return new SyncRecipeTypesPacket(out);
    }

    public static void handle(final SyncRecipeTypesPacket packet,
                              final net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        ctx.enqueueWork(() -> lastReceived = List.copyOf(packet.recipeTypes()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
