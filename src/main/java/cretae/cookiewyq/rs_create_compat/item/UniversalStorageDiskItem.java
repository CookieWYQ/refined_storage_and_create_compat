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
 * <p>分级版（1K/4K/16K/64K/256K/1M/4M/16M/64M/无限）
 * 通过 {@link #UniversalStorageDiskItem(long)} 传入固定容量（≤0 表示无限）。
 */
public class UniversalStorageDiskItem extends AbstractStorageContainerItem {
    private static final Component HELP_TEXT = Component.translatable(
        "item.rs_create_compat.universal_storage_disk.help");

    /** 分级容量（物品位）；≤0 表示无限（创造）。 */
    @Nullable
    private final Long tierCapacity;

    /** 分级版：容量固定；{@code capacity <= 0} 表示无限（创造级）。 */
    public UniversalStorageDiskItem(final long capacity) {
        this(Long.valueOf(capacity <= 0 ? Long.MIN_VALUE : capacity));
    }

    private UniversalStorageDiskItem(@Nullable final Long tierCapacity) {
        super(
            new Item.Properties().stacksTo(1).fireResistant(),
            RefinedStorageApi.INSTANCE.getStorageContainerItemHelper()
        );
        this.tierCapacity = tierCapacity;
    }

    @Nullable
    @Override
    protected Long getCapacity() {
        // 无限容量由 UniversalStorageType.create 识别为 null
        return tierCapacity == Long.MIN_VALUE ? null : tierCapacity;
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
        // 恢复 RS 原生「常显帮助」：帮助文本由 HelpTooltipComponent 一直显示，不再走按键分层。
        return Optional.of(new HelpTooltipComponent(HELP_TEXT));
    }
}
