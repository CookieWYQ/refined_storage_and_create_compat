package cretae.cookiewyq.rs_create_compat.block.entity;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 高级蓝图加农炮装填器：继承基础版，扩展蓝图队列自动打印流水线。
 * <ul>
 *     <li>主库存 108 格（基础版 54 格）。</li>
 *     <li>蓝图队列 27 格：点击开始后按顺序自动打印队列中的蓝图，
 *     流程：自动触发加农炮 → 自动获取资源 → 打印 → 自动回收空白蓝图 → 填入下一张 → 继续。</li>
 * </ul>
 */
public class AdvancedSchematicLoaderBlockEntity extends SchematicLoaderBlockEntity {
    private boolean queueRunning;

    public AdvancedSchematicLoaderBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.ADVANCED_SCHEMATIC_LOADER_BLOCK_ENTITY.get(), pos, state, 108, true);
    }

    public boolean isQueueRunning() {
        return queueRunning;
    }

    /** 点击开始/停止：切换队列自动打印。 */
    public void toggleQueue() {
        this.queueRunning = !this.queueRunning;
        setChanged();
    }

    /** 队列流程：优先从队列取下一张蓝图。 */
    @Override
    protected ItemStack getNextBlueprint() {
        if (queueRunning) {
            final ItemStack next = takeFromQueue();
            if (!next.isEmpty()) {
                return next;
            }
        }
        return super.getNextBlueprint();
    }

    private ItemStack takeFromQueue() {
        for (int i = 0; i < queue.getSlots(); i++) {
            final ItemStack stack = queue.getStackInSlot(i);
            if (!stack.isEmpty()) {
                final ItemStack taken = stack.copy();
                queue.setStackInSlot(i, ItemStack.EMPTY);
                return taken;
            }
        }
        return ItemStack.EMPTY;
    }

    private void insertIntoQueue(final ItemStack stack) {
        ItemStack remainder = stack;
        for (int i = 0; i < queue.getSlots() && !remainder.isEmpty(); i++) {
            remainder = queue.insertItem(i, remainder, false);
        }
    }

    /** 自动回收：打印完成后的空白蓝图放入队列末尾，便于继续下一张。 */
    @Override
    protected boolean isAutoRecycleActive() {
        return autoRecycle;
    }

    @Override
    protected void recycleFinishedBlueprint(final ItemStack out) {
        if (queueRunning) {
            insertIntoQueue(out);
        } else {
            blueprintSlot.setStackInSlot(0, out);
        }
    }

    /** 自动打印：队列运行或手动开关时触发。 */
    @Override
    protected boolean shouldAutoPrint() {
        return autoPrint || queueRunning;
    }

    /** 供菜单同步：0/1/2/3 = 开关，4 = 队列运行中。 */
    @Override
    public net.minecraft.world.inventory.ContainerData getContainerData() {
        return new net.minecraft.world.inventory.ContainerData() {
            @Override
            public int get(final int index) {
                return switch (index) {
                    case 0 -> autoPrint ? 1 : 0;
                    case 1 -> autoDeploy ? 1 : 0;
                    case 2 -> autoRecycle ? 1 : 0;
                    case 3 -> autoFillGunpowder ? 1 : 0;
                    case 4 -> queueRunning ? 1 : 0;
                    default -> 0;
                };
            }

            @Override
            public void set(final int index, final int value) {
                // 由服务端按钮逻辑修改
            }

            @Override
            public int getCount() {
                return 5;
            }
        };
    }

    @Override
    public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("QueueRunning", queueRunning);
    }

    @Override
    public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        queueRunning = tag.getBoolean("QueueRunning");
    }

    public static void registerCapabilities(final net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
            RS_Create_Compat.ADVANCED_SCHEMATIC_LOADER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getInventory()
        );
        event.registerBlockEntity(
            com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi.INSTANCE
                .getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.ADVANCED_SCHEMATIC_LOADER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
    }
}
