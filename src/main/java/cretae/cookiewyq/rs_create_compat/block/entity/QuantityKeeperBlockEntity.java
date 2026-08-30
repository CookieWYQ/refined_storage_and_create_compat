package cretae.cookiewyq.rs_create_compat.block.entity;

import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.network.QuantityKeeperNetworkNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * 定量保持器：标记一个对象（当前支持物品），
 * 数量不足时自动触发 RS 自动合成（需自动合成升级），超出时可自动销毁过量部分。
 * 拥有 6 个插件槽（速度升级加快销毁、自动合成升级启用合成）。
 */
public class QuantityKeeperBlockEntity extends AbstractBaseNetworkNodeContainerBlockEntity<QuantityKeeperNetworkNode> {
    private static final ResourceLocation SPEED_UPGRADE = ResourceLocation.fromNamespaceAndPath("refinedstorage", "speed_upgrade");
    private static final ResourceLocation AUTOCRAFTING_UPGRADE = ResourceLocation.fromNamespaceAndPath("refinedstorage", "autocrafting_upgrade");

    /** 标记槽（第 0 格） + 插件槽（6 格）。 */
    private final SimpleContainer inventory = new SimpleContainer(7);
    private int targetAmount = Config.quantityKeeperDefaultTarget;
    private boolean destroyOverflow = true;

    public QuantityKeeperBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.QUANTITY_KEEPER_BLOCK_ENTITY.get(), pos, state, new QuantityKeeperNetworkNode());
        this.mainNetworkNode.setBlockEntity(this);
        // 容量变化时刷新节点状态
        inventory.addListener(container -> setChanged());
    }

    public SimpleContainer getInventory() {
        return inventory;
    }

    public ItemStack getMarkerStack() {
        return inventory.getItem(0);
    }

    public int getTargetAmount() {
        return targetAmount;
    }

    public void setTargetAmount(final int targetAmount) {
        this.targetAmount = Math.max(1, targetAmount);
        setChanged();
    }

    public boolean isDestroyOverflow() {
        return destroyOverflow;
    }

    public void setDestroyOverflow(final boolean destroyOverflow) {
        this.destroyOverflow = destroyOverflow;
        setChanged();
    }

    public boolean hasAutocraftingUpgrade() {
        return countUpgrades(AUTOCRAFTING_UPGRADE) > 0;
    }

    public int getDestroyRate() {
        final int speed = countUpgrades(SPEED_UPGRADE);
        return Config.quantityKeeperDestroyRate + speed * Config.quantityKeeperDestroyRatePerSpeed;
    }

    private int countUpgrades(final ResourceLocation upgradeId) {
        final net.minecraft.world.item.Item upgradeItem = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(upgradeId);
        int count = 0;
        for (int i = 1; i < inventory.getContainerSize(); i++) {
            final ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(upgradeItem)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** 范围充电器不使用红石模式。 */
    @Override
    protected boolean hasRedstoneMode() {
        return false;
    }

    @Override
    public net.minecraft.network.chat.Component getName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("TargetAmount", targetAmount);
        tag.putBoolean("DestroyOverflow", destroyOverflow);
        tag.put("Inventory", inventory.createTag(registries));
    }

    @Override
    public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        targetAmount = Math.max(1, tag.getInt("TargetAmount"));
        destroyOverflow = tag.getBoolean("DestroyOverflow");
        if (tag.contains("Inventory")) {
            inventory.fromTag(tag.getList("Inventory", net.minecraft.nbt.Tag.TAG_COMPOUND), registries);
        }
    }

    /** 供菜单同步的实时数据：0 = 目标数量，1 = 销毁开关，2 = 速度升级数，3 = 自动合成升级数。 */
    public net.minecraft.world.inventory.ContainerData getContainerData() {
        return new net.minecraft.world.inventory.ContainerData() {
            @Override
            public int get(final int index) {
                return switch (index) {
                    case 0 -> targetAmount;
                    case 1 -> destroyOverflow ? 1 : 0;
                    case 2 -> countUpgrades(SPEED_UPGRADE);
                    case 3 -> hasAutocraftingUpgrade() ? 1 : 0;
                    default -> 0;
                };
            }

            @Override
            public void set(final int index, final int value) {
                // 由服务端按钮逻辑修改
            }

            @Override
            public int getCount() {
                return 4;
            }
        };
    }

    /** 向 NeoForge 注册网络节点容器能力（MOD 总线事件）。 */
    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.QUANTITY_KEEPER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
    }
}
