package cretae.cookiewyq.rs_create_compat.block.entity;

import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
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

import java.util.List;

/**
 * 范围充电器：为周围指定范围内的可充电方块与掉落物物品充电。
 * <p>
 * 范围在 GUI 中调整（三轴独立，默认 50×50×50，最小 1、最大 100）。
 * 为控制性能，范围方块按"分片"逐步扫描，每 tick 只检查一部分。
 */
public class RangeChargerBlockEntity extends BlockEntity {
    /** 每 tick 最多检查的方块数量（分片大小）。 */
    private static final int SCAN_SLICE_SIZE = 512;

    private final EnergyStorage energyStorage;
    private int rangeX = 50;
    private int rangeY = 50;
    private int rangeZ = 50;
    /** 分片扫描游标。 */
    private int scanOffset;

    public RangeChargerBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.RANGE_CHARGER_BLOCK_ENTITY.get(), pos, state);
        this.energyStorage = new EnergyStorage(
            Config.rangeChargerEnergyCapacity,
            Config.rangeChargerMaxTransfer
        );
    }

    public EnergyStorage getEnergyStorage() {
        return energyStorage;
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

    public static void serverTick(final Level level,
                                  final BlockPos pos,
                                  final BlockState state,
                                  final RangeChargerBlockEntity blockEntity) {
        blockEntity.tick(level);
    }

    private void tick(final Level level) {
        if (energyStorage.getEnergyStored() <= 0) {
            return;
        }
        // 待机扫描耗电：随范围增大而较快上升（默认 50^3 时约 25 FE/tick）
        final long volume = (long) (rangeX + 1) * (rangeY + 1) * (rangeZ + 1);
        final int scanCost = (int) Math.min(1000, volume / 5000);
        final int energyBefore = energyStorage.getEnergyStored();
        // 扫描耗电（若本次缓存充足则扣除）
        if (energyBefore <= scanCost) {
            return;
        }
        energyStorage.extractEnergy(scanCost, false);

        if (Config.rangeChargerChargeBlocks) {
            scanBlocks(level);
        }
        if (Config.rangeChargerChargeItems) {
            scanItems(level);
        }
    }

    /**
     * 分片扫描范围内方块：从 scanOffset 开始检查 SCAN_SLICE_SIZE 个坐标。
     */
    private void scanBlocks(final Level level) {
        final int sizeX = rangeX + 1;
        final int sizeY = rangeY + 1;
        final int sizeZ = rangeZ + 1;
        final long total = (long) sizeX * sizeY * sizeZ;
        if (total <= 0 || total > Integer.MAX_VALUE - 1) {
            return;
        }
        final int baseX = worldPosition.getX() - rangeX / 2;
        final int baseY = worldPosition.getY() - rangeY / 2;
        final int baseZ = worldPosition.getZ() - rangeZ / 2;

        int checked = 0;
        int targets = 0;
        long index = scanOffset;
        while (checked < SCAN_SLICE_SIZE && checked < total) {
            final long i = index % total;
            final int x = (int) (i / ((long) sizeY * sizeZ));
            final int rem = (int) (i % ((long) sizeY * sizeZ));
            final int y = rem / sizeZ;
            final int z = rem % sizeZ;
            if (chargeBlock(level, baseX + x, baseY + y, baseZ + z)) {
                targets++;
            }
            index++;
            checked++;
            if (targets >= Config.rangeChargerMaxTargets || energyStorage.getEnergyStored() <= 0) {
                break;
            }
        }
        scanOffset = (int) ((scanOffset + SCAN_SLICE_SIZE) % total);
    }

    private boolean chargeBlock(final Level level, final int x, final int y, final int z) {
        if (x == worldPosition.getX() && y == worldPosition.getY() && z == worldPosition.getZ()) {
            return false; // 跳过自身
        }
        final BlockPos pos = new BlockPos(x, y, z);
        final BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return false;
        }
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
    protected void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("RangeX", rangeX);
        tag.putInt("RangeY", rangeY);
        tag.putInt("RangeZ", rangeZ);
        tag.putInt("Energy", energyStorage.getEnergyStored());
    }

    @Override
    protected void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        rangeX = Math.clamp(tag.getInt("RangeX"), 1, 100);
        rangeY = Math.clamp(tag.getInt("RangeY"), 1, 100);
        rangeZ = Math.clamp(tag.getInt("RangeZ"), 1, 100);
        energyStorage.receiveEnergy(tag.getInt("Energy"), false);
    }

    /** 向 NeoForge 注册能量 capability（MOD 总线事件）。 */
    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            Capabilities.EnergyStorage.BLOCK,
            RS_Create_Compat.RANGE_CHARGER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getEnergyStorage()
        );
    }
}
