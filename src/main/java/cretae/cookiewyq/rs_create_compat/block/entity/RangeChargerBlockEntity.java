package cretae.cookiewyq.rs_create_compat.block.entity;

import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.network.RangeChargerNetworkNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity;

import java.util.List;

/**
 * 范围充电器：接入 RS 网络（能量线缆/控制器供能），
 * 为周围指定范围内的可充电方块与掉落物物品充电。
 * <p>
 * 范围在 GUI 中调整（三轴独立，默认 50×50×50，最小 1、最大 100）。
 * 为控制性能，范围方块按"分片"逐步扫描，每 tick 只检查一部分。
 */
public class RangeChargerBlockEntity extends AbstractBaseNetworkNodeContainerBlockEntity<RangeChargerNetworkNode> {
    private final EnergyStorage energyStorage;
    private int rangeX = 50;
    private int rangeY = 50;
    private int rangeZ = 50;

    public RangeChargerBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.RANGE_CHARGER_BLOCK_ENTITY.get(), pos, state, new RangeChargerNetworkNode());
        this.mainNetworkNode.setBlockEntity(this);
        this.energyStorage = new EnergyStorage(
            Config.rangeChargerEnergyCapacity,
            Config.rangeChargerMaxTransfer
        );
    }

    public EnergyStorage getEnergyStorage() {
        return energyStorage;
    }

    public RangeChargerNetworkNode getNode() {
        return mainNetworkNode;
    }

    /**
     * 供菜单同步的实时数据：0/1/2 = 三轴范围，3/4 = 能量/上限。
     */
    public net.minecraft.world.inventory.ContainerData getContainerData() {
        return new net.minecraft.world.inventory.ContainerData() {
            @Override
            public int get(final int index) {
                return switch (index) {
                    case 0 -> rangeX;
                    case 1 -> rangeY;
                    case 2 -> rangeZ;
                    case 3 -> energyStorage.getEnergyStored();
                    case 4 -> energyStorage.getMaxEnergyStored();
                    default -> 0;
                };
            }

            @Override
            public void set(final int index, final int value) {
                // 范围与能量只能由服务端逻辑修改
            }

            @Override
            public int getCount() {
                return 5;
            }
        };
    }

    public int getRangeX() {
        return rangeX;
    }

    public int getRangeY() {
        return rangeY;
    }

    public int getRangeZ() {
        return rangeZ;
    }

    public void adjustRangeX(final int delta) {
        rangeX = Math.clamp(rangeX + delta, 1, 100);
        setChanged();
    }

    public void adjustRangeY(final int delta) {
        rangeY = Math.clamp(rangeY + delta, 1, 100);
        setChanged();
    }

    public void adjustRangeZ(final int delta) {
        rangeZ = Math.clamp(rangeZ + delta, 1, 100);
        setChanged();
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

    /**
     * 由网络节点 ticker 每 tick 调用：先抽取网络能量，再执行充电扫描。
     */
    @Override
    public void doWork() {
        super.doWork(); // 节点 doWork：从 RS 网络抽取能量到缓存
        if (level != null && !level.isClientSide()) {
            doCharging(level);
        }
    }

    private void doCharging(final Level level) {
        if (energyStorage.getEnergyStored() <= 0) {
            return;
        }
        // 每 tick 都执行充电；扫描耗电很小，不再因能量不足而跳过
        if (Config.rangeChargerChargeBlocks) {
            scanBlocks(level);
        }
        if (Config.rangeChargerChargeItems) {
            scanItems(level);
        }
        if (Config.rangeChargerChargePlayerItems) {
            scanPlayers(level);
        }
    }

    /** 扫描范围内玩家，给其手持与背包中的可充电物品供电（如无线终端）。 */
    private void scanPlayers(final Level level) {
        final int halfX = rangeX / 2;
        final int halfY = rangeY / 2;
        final int halfZ = rangeZ / 2;
        final net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
            worldPosition.getX() - halfX, worldPosition.getY() - halfY, worldPosition.getZ() - halfZ,
            worldPosition.getX() + halfX + 1, worldPosition.getY() + halfY + 1, worldPosition.getZ() + halfZ + 1
        );
        final List<net.minecraft.world.entity.player.Player> players =
            level.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class, box, p -> !p.isSpectator());
        int targets = 0;
        for (final net.minecraft.world.entity.player.Player player : players) {
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                if (targets >= Config.rangeChargerMaxTargets || energyStorage.getEnergyStored() <= 0) {
                    return;
                }
                final ItemStack stack = player.getInventory().getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                final IEnergyStorage storage = stack.getCapability(Capabilities.EnergyStorage.ITEM);
                if (storage == null || storage.getEnergyStored() >= storage.getMaxEnergyStored()) {
                    continue;
                }
                final int transfer = Math.min(Config.rangeChargerChargeRate, energyStorage.getEnergyStored());
                final int accepted = storage.receiveEnergy(transfer, false);
                if (accepted > 0) {
                    energyStorage.extractEnergy(accepted, false);
                    targets++;
                }
            }
        }
    }

    /**
     * 扫描范围内所有已加载方块实体并充电。
     * 每 tick 全量遍历（只迭代实际存在的方块实体，性能好），
     * 最多给 maxTargets 个目标充电 —— 解决分片扫描导致方块充电过慢的问题。
     */
    private void scanBlocks(final Level level) {
        final int halfX = rangeX / 2;
        final int halfY = rangeY / 2;
        final int halfZ = rangeZ / 2;
        final int minX = worldPosition.getX() - halfX;
        final int maxX = worldPosition.getX() + halfX;
        final int minY = worldPosition.getY() - halfY;
        final int maxY = worldPosition.getY() + halfY;
        final int minZ = worldPosition.getZ() - halfZ;
        final int maxZ = worldPosition.getZ() + halfZ;

        int targets = 0;
        for (final java.util.Map.Entry<BlockPos, BlockEntity> entry : level.getBlockEntities().entrySet()) {
            if (targets >= Config.rangeChargerMaxTargets || energyStorage.getEnergyStored() <= 0) {
                return;
            }
            final BlockPos pos = entry.getKey();
            if (pos.getX() < minX || pos.getX() > maxX
                || pos.getY() < minY || pos.getY() > maxY
                || pos.getZ() < minZ || pos.getZ() > maxZ) {
                continue;
            }
            if (pos.equals(worldPosition)) {
                continue; // 跳过自身
            }
            if (chargeBlock(level, pos, entry.getValue())) {
                targets++;
            }
        }
    }

    private boolean chargeBlock(final Level level, final BlockPos pos, final BlockEntity blockEntity) {
        final IEnergyStorage storage = level.getCapability(
            Capabilities.EnergyStorage.BLOCK, pos, blockEntity.getBlockState(), blockEntity, null
        );
        if (storage == null || storage.getEnergyStored() >= storage.getMaxEnergyStored()) {
            return false;
        }
        final int transfer = Math.min(Config.rangeChargerChargeRate, energyStorage.getEnergyStored());
        final int accepted = storage.receiveEnergy(transfer, false);
        if (accepted > 0) {
            energyStorage.extractEnergy(accepted, false);
            return true;
        }
        return false;
    }

    private void scanItems(final Level level) {
        if (energyStorage.getEnergyStored() <= 0) {
            return;
        }
        final int halfX = rangeX / 2;
        final int halfY = rangeY / 2;
        final int halfZ = rangeZ / 2;
        final net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
            worldPosition.getX() - halfX, worldPosition.getY() - halfY, worldPosition.getZ() - halfZ,
            worldPosition.getX() + halfX + 1, worldPosition.getY() + halfY + 1, worldPosition.getZ() + halfZ + 1
        );
        final List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box, ItemEntity::isAlive);
        int targets = 0;
        for (final ItemEntity itemEntity : items) {
            if (targets >= Config.rangeChargerMaxTargets || energyStorage.getEnergyStored() <= 0) {
                break;
            }
            final ItemStack stack = itemEntity.getItem();
            final IEnergyStorage storage = stack.getCapability(Capabilities.EnergyStorage.ITEM);
            if (storage == null || storage.getEnergyStored() >= storage.getMaxEnergyStored()) {
                continue;
            }
            final int transfer = Math.min(Config.rangeChargerChargeRate, energyStorage.getEnergyStored());
            final int accepted = storage.receiveEnergy(transfer, false);
            if (accepted > 0) {
                energyStorage.extractEnergy(accepted, false);
                targets++;
            }
        }
    }

    @Override
    public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("RangeX", rangeX);
        tag.putInt("RangeY", rangeY);
        tag.putInt("RangeZ", rangeZ);
        tag.putInt("Energy", energyStorage.getEnergyStored());
    }

    @Override
    public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        rangeX = Math.clamp(tag.getInt("RangeX"), 1, 100);
        rangeY = Math.clamp(tag.getInt("RangeY"), 1, 100);
        rangeZ = Math.clamp(tag.getInt("RangeZ"), 1, 100);
        energyStorage.receiveEnergy(tag.getInt("Energy"), false);
    }

    /** 向 NeoForge 注册能量与网络节点容器能力（MOD 总线事件）。 */
    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            Capabilities.EnergyStorage.BLOCK,
            RS_Create_Compat.RANGE_CHARGER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getEnergyStorage()
        );
        // 注册为 RS 网络节点容器，使 RS 线缆/控制器可以连接并为节点供能
        event.registerBlockEntity(
            RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.RANGE_CHARGER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
    }
}
