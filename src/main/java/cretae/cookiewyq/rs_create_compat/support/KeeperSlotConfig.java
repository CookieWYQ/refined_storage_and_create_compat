package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * 高级物品定量保持器的「单槽位配置」。
 * <ul>
 *     <li>{@code form}：标记形态 —— {@link #FORM_ITEM 0=物品} / {@link #FORM_FLUID 1=流体} /
 *     {@link #FORM_GAS 2=气体} / {@link #FORM_EMPTY 3=空槽}；<b>空槽允许存在，顺序无关</b>。</li>
 *     <li>{@code id}：被标记资源的注册名（物品或流体；空槽为 null）。</li>
 *     <li>{@code nbt}：被标记资源的数据组件补丁编码（物品 = 组件，流体 = 组件；不透明负载）。</li>
 *     <li>{@code amount}：目标数量（物品按个、流体/气体按 mB）。</li>
 *     <li>{@code autocraft}：该槽是否开启自动合成。</li>
 * </ul>
 * 本记录是「后端逻辑 + 同步包」共用的只读视图，不直接持有存储。
 */
public record KeeperSlotConfig(int form,
                               @Nullable ResourceLocation id,
                               CompoundTag nbt,
                               long amount,
                               boolean autocraft) {
    /** 标记形态：物品。 */
    public static final int FORM_ITEM = 0;
    /** 标记形态：流体。 */
    public static final int FORM_FLUID = 1;
    /** 标记形态：气体（Mekanism 化学品同样以流体标识承载）。 */
    public static final int FORM_GAS = 2;
    /** 标记形态：空槽（允许存在，不应用任何约束）。 */
    public static final int FORM_EMPTY = 3;

    public static final KeeperSlotConfig EMPTY =
        new KeeperSlotConfig(FORM_EMPTY, null, new CompoundTag(), 0L, false);

    /** 空槽（form=3 或没有资源 id）。 */
    public boolean isEmpty() {
        return form == FORM_EMPTY || id == null;
    }

    /** 是否为流体 / 气体标记。 */
    public boolean isFluid() {
        return form == FORM_FLUID || form == FORM_GAS;
    }

    /** 是否为物品标记。 */
    public boolean isItem() {
        return form == FORM_ITEM && id != null;
    }

    /** 归一化的数据组件 NBT（永不返回 null）。 */
    @Override
    public CompoundTag nbt() {
        return nbt == null ? new CompoundTag() : nbt;
    }
}
