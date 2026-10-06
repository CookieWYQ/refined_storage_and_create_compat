package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReferenceFactory;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.AdvancedRemoteTerminalItem;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 「背包任意格」的终端槽位引用：把玩家背包（含快捷栏）里<b>某一确定的格子</b>包成 RS 的
 * {@link SlotReference}，供「模式切换 Tab」这类客户端功能在<b>不手持</b>终端时也能定位到它。
 * <p>
 * <b>为什么必须自己造一个引用类型</b>：RS 只对外提供
 * {@code RefinedStorageApi#createInventorySlotReference(player, hand)}（只能指向主手 / 副手），
 * 它自带的 {@code InventorySlotReference} 构造器是包内可见、无法指向任意下标。
 * 而用户的用法正是「终端放在快捷栏 / 背包里，按快捷键打开」—— 此时手上往往不是终端，
 * 只认双手的旧实现就找不到它（表现为「右下角那一排切换界面的按钮不见了」）。
 * <p>
 * <b>引用只存下标、不存物品快照</b>：双端按同一下标重新取值，避免物品实例在网络上传来传去；
 * 下标越界 / 该格已空 → {@link Optional#empty()}，服务端据此拒绝切换模式。
 * <p>
 * 该引用类型只在<b>客户端</b>被构造（服务端只负责解码 + 解引用），但工厂必须在双端注册
 * （RS 的 {@code UseSlotReferencedItemPacket} 靠 {@code SlotReferenceFactory#STREAM_CODEC} 传输）。
 */
public final class RsccTerminalReferences {
    private static final ResourceLocation INVENTORY_FACTORY_ID =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "inventory_terminal_slot");
    private static final SlotReferenceFactory INVENTORY_FACTORY = new InventorySlotRefFactory();
    /** 背包中要扫描的格数：快捷栏 0..8 + 主背包 9..35（盔甲 36..39 与副手 40 不在此档位内）。 */
    private static final int INVENTORY_SLOTS = 36;

    private RsccTerminalReferences() {
    }

    /** 注册背包引用工厂（common 端在 {@code commonSetup} 调用一次；双端都要有）。 */
    public static void register() {
        RefinedStorageApi.INSTANCE.getSlotReferenceFactoryRegistry()
            .register(INVENTORY_FACTORY_ID, INVENTORY_FACTORY);
    }

    /**
     * 背包（快捷栏 0..8 + 主背包 9..35）里<b>全部</b>高级远程多功能终端的槽位引用，按下标升序。
     * <p>扫描范围刻意排除副手（下标 40）与盔甲槽：副手的情况由 RS 原生的
     * {@code createInventorySlotReference(player, OFF_HAND)} 覆盖，且调用方会先查双手，
     * 因此这里只需要覆盖「背包里（非手持）」这一种情形（与 {@code RsccTerminalLocator} 的
     * 「背包/快捷栏下标 0..35」这一优先级档位完全一致）。</p>
     */
    public static java.util.List<SlotReference> findAllInventoryTerminals(final Player player) {
        final java.util.List<SlotReference> found = new java.util.ArrayList<>(2);
        for (int index = 0; index < INVENTORY_SLOTS; index++) {
            final ItemStack stack = player.getInventory().getItem(index);
            if (!stack.isEmpty() && stack.getItem() instanceof AdvancedRemoteTerminalItem) {
                found.add(new InventorySlotRef(index));
            }
        }
        return found;
    }

    /** 找玩家背包里第一台高级远程多功能终端（等价于 {@link #findAllInventoryTerminals} 的第一个）。 */
    public static Optional<SlotReference> findInventoryTerminal(final Player player) {
        return findAllInventoryTerminals(player).stream().findFirst();
    }

    /** 指向「背包第 index 格」的引用（服务端按固定优先级定位到具体某一格时使用）。 */
    public static SlotReference createSlotReference(final int index) {
        return new InventorySlotRef(index);
    }

    /** 指向「玩家背包第 index 格」的引用（未手持时的终端定位用）。 */
    private record InventorySlotRef(int index) implements SlotReference {
        /**
         * 该格就是「正在被使用的终端」，因此要像 RS 自带的背包引用一样声明「此槽禁用」——
         * 否则界面允许玩家把它搬走，引用会在半路失效。
         */
        @Override
        public boolean isDisabledSlot(final int playerSlotIndex) {
            return playerSlotIndex == index;
        }

        @Override
        public Optional<ItemStack> resolve(final Player player) {
            final int size = player.getInventory().getContainerSize();
            if (index < 0 || index >= size) {
                return Optional.empty();
            }
            final ItemStack stack = player.getInventory().getItem(index);
            return stack.isEmpty() ? Optional.empty() : Optional.of(stack);
        }

        @Override
        public SlotReferenceFactory getFactory() {
            return INVENTORY_FACTORY;
        }
    }

    /** 引用的网络序列化（只传一个下标）。 */
    private static final class InventorySlotRefFactory implements SlotReferenceFactory {
        private static final StreamCodec<RegistryFriendlyByteBuf, InventorySlotRef> STREAM_CODEC =
            StreamCodec.composite(
                ByteBufCodecs.VAR_INT, InventorySlotRef::index,
                InventorySlotRef::new
            );

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public StreamCodec<RegistryFriendlyByteBuf, SlotReference> getStreamCodec() {
            return (StreamCodec) STREAM_CODEC;
        }
    }
}
