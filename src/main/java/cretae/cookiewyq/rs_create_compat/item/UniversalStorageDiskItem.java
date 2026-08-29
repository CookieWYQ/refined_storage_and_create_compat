package cretae.cookiewyq.rs_create_compat.item;

import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.storage.AbstractStorageContainerItem;
import com.refinedmods.refinedstorage.common.api.storage.SerializableStorage;
import com.refinedmods.refinedstorage.common.api.storage.StorageRepository;
import com.refinedmods.refinedstorage.common.api.support.HelpTooltipComponent;
import com.refinedmods.refinedstorage.common.util.IdentifierUtil;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.storage.UniversalStorageType;

import java.util.Optional;
import javax.annotation.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 通用储存磁盘：可同时存入物品、流体、气体任意类型。
 * 容量换算：1 bucket（1000 mB）= 1 物品位。
 */
public class UniversalStorageDiskItem extends AbstractStorageContainerItem {
    private static final Component HELP_TEXT = Component.translatable(
        "item.rs_create_compat.universal_storage_disk.help");

    public UniversalStorageDiskItem() {
        super(
            new Item.Properties().stacksTo(1).fireResistant(),
            RefinedStorageApi.INSTANCE.getStorageContainerItemHelper()
        );
    }

    @Nullable
    @Override
    protected Long getCapacity() {
        return (long) Config.universalDiskBaseCapacity;
    }

    @Override
    protected String formatAmount(final long amount) {
        return IdentifierUtil.format(amount);
    }

    @Override
    protected SerializableStorage createStorage(final StorageRepository storageRepository) {
        return UniversalStorageType.INSTANCE.create(getCapacity(), storageRepository::markAsChanged);
    }

    @Override
    protected ItemStack createPrimaryDisassemblyByproduct(final int count) {
        return ItemStack.EMPTY;
    }

    @Nullable
    @Override
    protected ItemStack createSecondaryDisassemblyByproduct(final int count) {
        return null;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(final Level level,
                                                  final Player player,
                                                  final InteractionHand hand) {
        // 通用磁盘不支持拆解，右键不做任何事
        return InteractionResultHolder.pass(player.getItemInHand(hand));
    }

    @Override
    public Optional<TooltipComponent> getTooltipImage(final ItemStack stack) {
        return Optional.of(new HelpTooltipComponent(HELP_TEXT));
    }
}
