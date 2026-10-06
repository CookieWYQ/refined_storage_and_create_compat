package cretae.cookiewyq.rs_create_compat.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 「阻塞名单」的整份快照（{@link SyncCollectionMarkersPacket} 的一个组件）。
 *
 * <p><b>为什么单独一个记录而不是给同步包再加两个字段</b>：MC 的
 * {@code StreamCodec.composite} 最多只支持 <b>6</b> 组键值对，而本包原本已有 5 组；
 * 直接再加「物品标签 / 流体标签」两组会编译不过。把四个名单打包成一组件后，
 * 包仍是 4 组键值对（窗口起点 / 匹配条目 / 流体缓存 / 本快照），且语义更清楚。</p>
 *
 * @param items     被阻塞的<b>具体物品 id</b>
 * @param fluids    被阻塞的<b>具体流体 id</b>
 * @param itemTags  被阻塞的<b>物品标签</b>（按标签注册的匹配条目点阻塞后写在这里 ——
 *                  属于该标签的任何物品都被挡住，见用户第 ③ 条）
 * @param fluidTags 被阻塞的<b>流体标签</b>
 */
public record CollectionBlockedSnapshot(List<ResourceLocation> items, List<ResourceLocation> fluids,
                                        List<ResourceLocation> itemTags, List<ResourceLocation> fluidTags) {
    public static final CollectionBlockedSnapshot EMPTY =
        new CollectionBlockedSnapshot(List.of(), List.of(), List.of(), List.of());

    public static final StreamCodec<RegistryFriendlyByteBuf, CollectionBlockedSnapshot> STREAM_CODEC =
        StreamCodec.composite(
            ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), CollectionBlockedSnapshot::items,
            ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), CollectionBlockedSnapshot::fluids,
            ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), CollectionBlockedSnapshot::itemTags,
            ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), CollectionBlockedSnapshot::fluidTags,
            CollectionBlockedSnapshot::new
        );

    public static CollectionBlockedSnapshot of(final List<ResourceLocation> items,
                                               final List<ResourceLocation> fluids,
                                               final List<ResourceLocation> itemTags,
                                               final List<ResourceLocation> fluidTags) {
        return new CollectionBlockedSnapshot(
            items == null ? List.of() : List.copyOf(items),
            fluids == null ? List.of() : List.copyOf(fluids),
            itemTags == null ? List.of() : List.copyOf(itemTags),
            fluidTags == null ? List.of() : List.copyOf(fluidTags));
    }
}
