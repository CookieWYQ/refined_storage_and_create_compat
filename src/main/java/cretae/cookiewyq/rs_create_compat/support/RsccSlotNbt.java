package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/**
 * 「按<b>槽位下标</b>」读写 {@link SimpleContainer} 的 NBT 工具（本模组所有带界面槽位的机器共用一份实现）。
 *
 * <h2>为什么必须有它（真 bug 根因）</h2>
 * <p>原版的 {@code SimpleContainer#createTag} <b>只写物品、不写 "Slot"</b>；与之配套的
 * {@code SimpleContainer#fromTag} 也不是「按下标放回」，而是逐条调用 {@code addItem}：
 * <b>先并入同类已有堆、再塞进第一个空槽</b>。于是玩家把 3 个堆叠升级放在 4/5/6 三个不同格，
 * 存盘再读回后会被<b>挤进同一格</b>（甚至落到靠前的 ghost 标记槽 / 槽 0）——
 * 这正是用户反复报的「插件被挤到一格」，且它发生在<b>每一次</b> NBT 往返
 * （区块存档、{@code BlockItem} 的 {@code BLOCK_ENTITY_DATA} 挖起再放下，两条路径都走
 * {@code saveAdditional}/{@code loadAdditional}）。</p>
 *
 * <p>本类与 {@code ContainerHelper#saveAllItems} 完全同一写法（{@code entry.putByte("Slot", i)} +
 * {@code stack.save(registries, entry)}），因此不引入任何自定义物品编码；同时
 * <b>向后兼容</b>：读回时若列表里<b>没有</b> "Slot"（旧存档 / 旧方块物品），退回原版
 * {@code fromTag} 的兼容搬运 —— 只做一次、<b>绝不丢物</b>，下次存盘即自动升级为新格式。</p>
 *
 * <p><b>为什么写 NonNullList 而不是 {@code SimpleContainer#setItem}</b>：{@code setItem} 会按
 * {@code getMaxStackSize(stack)}（普通物品 = 64）截断；而缓存仓 / 保持器的某些格子经堆叠升级后
 * 可以合法持有超过 64 个。直接写内部 {@code NonNullList} 与 {@code ContainerHelper#loadAllItems}
 * 一致，既不截断也不触发加载期监听器。</p>
 */
public final class RsccSlotNbt {
    /** 槽位下标键（与 {@code ContainerHelper} / 原版容器 NBT 同一约定）。 */
    private static final String TAG_SLOT = "Slot";

    private RsccSlotNbt() {
    }

    /** 逐格写出带 "Slot" 下标的物品列表（空槽跳过；调用方负责放进自己的键下）。 */
    public static ListTag write(final SimpleContainer container, final HolderLookup.Provider registries) {
        final NonNullList<ItemStack> items = container.getItems();
        final ListTag list = new ListTag();
        for (int i = 0; i < items.size(); i++) {
            final ItemStack stack = items.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            final CompoundTag entry = new CompoundTag();
            entry.putByte(TAG_SLOT, (byte) i);
            list.add(stack.save(registries, entry));
        }
        return list;
    }

    /**
     * 读回容器：<b>有 "Slot" 就逐格放回原槽</b>；没有（旧档 / 旧方块物品）才退回原版
     * {@code SimpleContainer#fromTag} 的兼容搬运（只做一次、不丢物，下次存盘转为新格式）。
     *
     * <p>读之前先清空容器（避免两次 {@code loadAdditional} 叠加出重复内容），读完后
     * {@code setChanged()} 维持与旧实现一致的「已变更」副作用（监听器仍会被通知）。</p>
     */
    public static void read(final ListTag list, final SimpleContainer container,
                            final HolderLookup.Provider registries) {
        final NonNullList<ItemStack> items = container.getItems();
        for (int i = 0; i < items.size(); i++) {
            items.set(i, ItemStack.EMPTY);
        }
        boolean slotAware = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.getCompound(i).contains(TAG_SLOT)) {
                slotAware = true;
                break;
            }
        }
        if (!slotAware) {
            container.fromTag(list, registries); // 旧档兼容：无槽位信息，按原版 addItem 合并（不丢物）
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag entry = list.getCompound(i);
            final int slot = entry.getByte(TAG_SLOT) & 255;
            if (slot >= 0 && slot < items.size()) {
                items.set(slot, ItemStack.parse(registries, entry).orElse(ItemStack.EMPTY));
            }
        }
        container.setChanged();
    }
}
