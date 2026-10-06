package cretae.cookiewyq.rs_create_compat.support;

import com.mojang.serialization.DynamicOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 归流缓存仓「匹配区」的一条 ghost 标记（冻结契约 §2.3）。
 * <ul>
 *     <li>{@code fluid=false} → 物品标记，{@code id} 为物品注册名；</li>
 *     <li>{@code fluid=true} → 流体/气体标记，{@code id} 为流体注册名；</li>
 *     <li>{@code nbt} = 被标记资源的<b>数据组件补丁</b>（{@link DataComponentPatch}）以
 *     {@link NbtOps}（带注册表上下文）编码成的 NBT；空标记 / 世界流体方块为 {@code nbt.isEmpty()}；
 *     <b>对前端而言是不透明负载</b>：从同步包拿到什么就回传什么即可；</li>
 *     <li>{@code amount} = 需求量（物品按个数、流体按 mB）；</li>
 *     <li>{@code matchNbt} = 严格匹配数据组件（可与标签匹配同时生效）；</li>
 *     <li>{@code tags} = <b>匹配标签集合</b>（匹配一「类」资源）：非空时该条目按标签匹配，
 *     命中的资源必须<b>同时带上集合里的每一个标签</b>（AND）。标签从被标记物自身的标签里多选得到，
 *     因此示例物必然命中；{@code id} 作为展示代表物保留。标签与 NBT 规则可同时生效。</li>
 * </ul>
 * 未标记的格子用 {@link #EMPTY}（{@code id == null}）表示。
 */
public record MarkerEntry(boolean fluid,
                          @Nullable ResourceLocation id,
                          CompoundTag nbt,
                          long amount,
                          boolean matchNbt,
                          List<ResourceLocation> tags) {

    public static final MarkerEntry EMPTY =
        new MarkerEntry(false, null, new CompoundTag(), 0L, false, List.of());

    public static final StreamCodec<RegistryFriendlyByteBuf, MarkerEntry> STREAM_CODEC = StreamCodec.of(
        (buf, entry) -> {
            final boolean present = entry.id() != null;
            ByteBufCodecs.BOOL.encode(buf, present);
            if (present) {
                ResourceLocation.STREAM_CODEC.encode(buf, entry.id());
            }
            ByteBufCodecs.BOOL.encode(buf, entry.fluid());
            ByteBufCodecs.COMPOUND_TAG.encode(buf, entry.nbt() == null ? new CompoundTag() : entry.nbt());
            ByteBufCodecs.VAR_LONG.encode(buf, entry.amount());
            ByteBufCodecs.BOOL.encode(buf, entry.matchNbt());
            final List<ResourceLocation> tags = entry.tags() == null ? List.of() : entry.tags();
            ByteBufCodecs.VAR_INT.encode(buf, tags.size());
            for (final ResourceLocation tag : tags) {
                ResourceLocation.STREAM_CODEC.encode(buf, tag);
            }
        },
        buf -> {
            final ResourceLocation id = ByteBufCodecs.BOOL.decode(buf)
                ? ResourceLocation.STREAM_CODEC.decode(buf)
                : null;
            final boolean fluid = ByteBufCodecs.BOOL.decode(buf);
            final CompoundTag nbt = ByteBufCodecs.COMPOUND_TAG.decode(buf);
            final long amount = ByteBufCodecs.VAR_LONG.decode(buf);
            final boolean matchNbt = ByteBufCodecs.BOOL.decode(buf);
            final int tagCount = Math.max(0, ByteBufCodecs.VAR_INT.decode(buf));
            final List<ResourceLocation> tags = new ArrayList<>(tagCount);
            for (int i = 0; i < tagCount; i++) {
                tags.add(ResourceLocation.STREAM_CODEC.decode(buf));
            }
            return new MarkerEntry(fluid, id, nbt, amount, matchNbt, List.copyOf(tags));
        }
    );

    /** 由手持物品创建物品标记（{@code amount} 至少 1；{@code registries} 供数据组件编解码，可为 null）。 */
    public static MarkerEntry item(final ItemStack stack, final long amount, final boolean matchNbt,
                                   @Nullable final HolderLookup.Provider registries) {
        final ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return new MarkerEntry(false, id, encodeComponents(stack.getComponentsPatch(), registries),
            Math.max(1L, amount), matchNbt, List.of());
    }

    /** 由流体注册名创建流体/气体标记（世界流体方块没有数据组件，故 nbt 为空）。 */
    public static MarkerEntry fluidEntry(final ResourceLocation id, final long amount,
                                         final boolean matchNbt) {
        return new MarkerEntry(true, id, new CompoundTag(), Math.max(1L, amount), matchNbt, List.of());
    }

    /** 该条目是否是「按标签匹配」（标签集合非空）。 */
    public boolean isTagFilter() {
        return tags != null && !tags.isEmpty();
    }

    /** 该条目是否为空（未标记）。 */
    public boolean isEmpty() {
        return id == null;
    }

    /** 复制并替换匹配标签集合。 */
    public MarkerEntry withTags(final List<ResourceLocation> newTags) {
        return new MarkerEntry(fluid, id, nbt, amount, matchNbt,
            newTags == null ? List.of() : List.copyOf(newTags));
    }

    /** 复制并替换数量 / NBT 标记 / 标签集合。 */
    public MarkerEntry withRules(final long newAmount, final boolean newMatchNbt,
                                 final List<ResourceLocation> newTags) {
        return new MarkerEntry(fluid, id, nbt, Math.max(1L, newAmount), newMatchNbt,
            newTags == null ? List.of() : List.copyOf(newTags));
    }

    /** 物品标记的展示用物品（流体标记 / 空条目返回 {@link ItemStack#EMPTY}）。 */
    public ItemStack displayStack() {
        if (fluid || id == null) {
            return ItemStack.EMPTY;
        }
        final Item item = BuiltInRegistries.ITEM.get(id);
        return item == null || item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    /** 便捷重载：不带注册表上下文（编码失败返回空 CompoundTag，绝不抛异常）。 */
    public static CompoundTag encodeComponents(final DataComponentPatch patch) {
        return encodeComponents(patch, null);
    }

    /** 数据组件补丁 → NBT（空补丁返回空 CompoundTag；编码失败也返回空 CompoundTag，绝不抛异常）。 */
    public static CompoundTag encodeComponents(final DataComponentPatch patch,
                                               @Nullable final HolderLookup.Provider registries) {
        if (patch == null || patch.isEmpty()) {
            return new CompoundTag();
        }
        final DynamicOps<Tag> ops = ops(registries);
        final Tag tag = DataComponentPatch.CODEC.encodeStart(ops, patch).result().orElse(null);
        return tag instanceof CompoundTag compound ? compound : new CompoundTag();
    }

    /** 便捷重载：不带注册表上下文（解析失败按空补丁处理）。 */
    public static DataComponentPatch decodeComponents(@Nullable final CompoundTag tag) {
        return decodeComponents(tag, null);
    }

    /** NBT → 数据组件补丁（解析失败按空补丁处理，绝不抛异常）。 */
    public static DataComponentPatch decodeComponents(@Nullable final CompoundTag tag,
                                                      @Nullable final HolderLookup.Provider registries) {
        if (tag == null || tag.isEmpty()) {
            return DataComponentPatch.EMPTY;
        }
        return DataComponentPatch.CODEC.parse(ops(registries), tag).result().orElse(DataComponentPatch.EMPTY);
    }

    private static DynamicOps<Tag> ops(@Nullable final HolderLookup.Provider registries) {
        return registries == null ? NbtOps.INSTANCE : RegistryOps.create(NbtOps.INSTANCE, registries);
    }
}
