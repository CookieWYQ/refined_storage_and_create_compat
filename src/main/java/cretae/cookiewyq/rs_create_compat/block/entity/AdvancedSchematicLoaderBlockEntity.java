package cretae.cookiewyq.rs_create_compat.block.entity;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity.State;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.network.AdvancedSchematicLoaderNetworkNode;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.ItemStackHandler;

import javax.annotation.Nullable;

/**
 * 高级蓝图加农炮装填器：
 * <ul>
 *     <li>主库存 108 格（2 个大箱子）。</li>
 *     <li>蓝图队列存储区 27 格（存放待打印的已部署蓝图）。</li>
 *     <li>点击开始后按顺序自动打印队列中的蓝图：
 *     自动触发加农炮 → 自动获取资源 → 打印 → 自动回收空白蓝图 → 填入下一张 → 继续。</li>
 *     <li>继承基础版全部功能（开关、协同、插件槽）。</li>
 * </ul>
 */
public class AdvancedSchematicLoaderBlockEntity extends AbstractBaseNetworkNodeContainerBlockEntity<AdvancedSchematicLoaderNetworkNode> {
    private static final ResourceLocation SPEED_UPGRADE = ResourceLocation.fromNamespaceAndPath("refinedstorage", "speed_upgrade");
    private static final ResourceLocation AUTOCRAFTING_UPGRADE = ResourceLocation.fromNamespaceAndPath("refinedstorage", "autocrafting_upgrade");

    /** 主库存：108 格。 */
    private final ItemStackHandler inventory = new ItemStackHandler(108);
    /** 蓝图队列：27 格。 */
    private final ItemStackHandler queue = new ItemStackHandler(27);
    /** 插件槽：6 格。 */
    private final ItemStackHandler upgradeContainer = new ItemStackHandler(6);

    private boolean autoPrint;
    private boolean autoDeploy = true;
    private boolean autoRecycle = true;
    private boolean autoFillGunpowder = true;
    private boolean queueRunning;

    public AdvancedSchematicLoaderBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.ADVANCED_SCHEMATIC_LOADER_BLOCK_ENTITY.get(), pos, state,
            new AdvancedSchematicLoaderNetworkNode());
        this.mainNetworkNode.setBlockEntity(this);
    }

    public ItemStackHandler getInventory() {
        return inventory;
    }

    public ItemStackHandler getQueue() {
        return queue;
    }

    public ItemStackHandler getUpgradeContainer() {
        return upgradeContainer;
    }

    public boolean isAutoPrint() {
        return autoPrint;
    }

    public void setAutoPrint(final boolean autoPrint) {
        this.autoPrint = autoPrint;
        setChanged();
    }

    public boolean isAutoDeploy() {
        return autoDeploy;
    }

    public void setAutoDeploy(final boolean autoDeploy) {
        this.autoDeploy = autoDeploy;
        setChanged();
    }

    public boolean isAutoRecycle() {
        return autoRecycle;
    }

    public void setAutoRecycle(final boolean autoRecycle) {
        this.autoRecycle = autoRecycle;
        setChanged();
    }

    public boolean isAutoFillGunpowder() {
        return autoFillGunpowder;
    }

    public void setAutoFillGunpowder(final boolean autoFillGunpowder) {
        this.autoFillGunpowder = autoFillGunpowder;
        setChanged();
    }

    public boolean isQueueRunning() {
        return queueRunning;
    }

    /** 点击开始/停止：切换队列自动打印。 */
    public void toggleQueue() {
        this.queueRunning = !this.queueRunning;
        setChanged();
    }

    public long getEnergyUsage() {
        return 10;
    }

    private boolean hasAutocraftingUpgrade() {
        final Item upgradeItem = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(AUTOCRAFTING_UPGRADE);
        for (int i = 0; i < upgradeContainer.getSlots(); i++) {
            if (!upgradeContainer.getStackInSlot(i).isEmpty()
                && upgradeContainer.getStackInSlot(i).is(upgradeItem)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean hasRedstoneMode() {
        return false;
    }

    /** 由网络节点每 tick 驱动。 */
    public void doLoaderWork(final Network network) {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        network.getComponent(EnergyNetworkComponent.class).extract(getEnergyUsage());

        final SchematicannonBlockEntity cannon = findAttachedCannon(level);
        if (cannon == null) {
            return;
        }

        // 队列流程：无蓝图时从队列取下一张
        if (queueRunning && cannon.inventory.getStackInSlot(0).isEmpty()) {
            final ItemStack next = takeFromQueue();
            if (!next.isEmpty()) {
                cannon.inventory.setStackInSlot(0, next);
                cannon.sendUpdate = true;
            }
        }

        if (autoDeploy) {
            final ItemStack blueprint = cannon.inventory.getStackInSlot(0);
            if (blueprint.isEmpty() && queueRunning) {
                final ItemStack next = takeFromQueue();
                if (!next.isEmpty()) {
                    cannon.inventory.setStackInSlot(0, next);
                    cannon.sendUpdate = true;
                }
            }
        }

        cannon.updateChecklist();
        restockFromNetwork(cannon, network);

        if (autoFillGunpowder) {
            restockGunpowder(network);
        }

        // 自动回收：打印完成后空白蓝图回收到队列末尾（便于继续下一张）
        if (autoRecycle && cannon.blocksToPlace > 0 && cannon.blocksPlaced >= cannon.blocksToPlace) {
            final ItemStack out = cannon.inventory.getStackInSlot(1);
            if (!out.isEmpty()) {
                insertIntoQueue(out.copy());
                cannon.inventory.setStackInSlot(1, ItemStack.EMPTY);
                cannon.sendUpdate = true;
            }
        }

        // 自动打印
        if ((autoPrint || queueRunning) && cannon.state == State.STOPPED
            && cannon.printer.isLoaded() && isResourcesReady(cannon)) {
            cannon.state = State.RUNNING;
            cannon.sendUpdate = true;
        }
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

    private SchematicannonBlockEntity findAttachedCannon(final Level level) {
        for (final Direction direction : Direction.values()) {
            final BlockEntity blockEntity = level.getBlockEntity(worldPosition.relative(direction));
            if (blockEntity instanceof SchematicannonBlockEntity cannon) {
                return cannon;
            }
        }
        return null;
    }

    private void restockFromNetwork(final SchematicannonBlockEntity cannon, final Network network) {
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        final AutocraftingNetworkComponent autocrafting = network.getComponent(AutocraftingNetworkComponent.class);
        for (final Object2IntMap.Entry<Item> entry : cannon.checklist.required.object2IntEntrySet()) {
            final Item item = entry.getKey();
            final int needed = entry.getIntValue();
            final int gathered = cannon.checklist.gathered.getInt(item);
            final int missingForCannon = needed - gathered;
            if (missingForCannon <= 0) {
                continue;
            }
            final int allLoadersHave = countItemInCluster(item);
            final int toFetch = missingForCannon - allLoadersHave;
            if (toFetch <= 0) {
                continue;
            }
            final ResourceKey resource = new ItemResource(item, DataComponentPatch.EMPTY);
            final long inNetwork = storage.get(resource);
            if (inNetwork >= toFetch) {
                final long extracted = storage.extract(resource, toFetch, Action.EXECUTE, Actor.EMPTY);
                if (extracted > 0) {
                    insertIntoInventory(new ItemResource(item, DataComponentPatch.EMPTY).toItemStack(extracted));
                }
            } else if (hasAutocraftingUpgrade()) {
                autocrafting.ensureTask(resource, toFetch - inNetwork, Actor.EMPTY, null);
            }
        }
    }

    private void restockGunpowder(final Network network) {
        if (countItem(Items.GUNPOWDER) >= 128) {
            return;
        }
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        final ResourceKey resource = new ItemResource(Items.GUNPOWDER, DataComponentPatch.EMPTY);
        final long inNetwork = storage.get(resource);
        if (inNetwork <= 0) {
            return;
        }
        final long toFetch = Math.min(inNetwork, 128 - countItem(Items.GUNPOWDER));
        final long extracted = storage.extract(resource, toFetch, Action.EXECUTE, Actor.EMPTY);
        if (extracted > 0) {
            insertIntoInventory(new ItemResource(Items.GUNPOWDER, DataComponentPatch.EMPTY).toItemStack(extracted));
        }
    }

    private boolean isResourcesReady(final SchematicannonBlockEntity cannon) {
        for (final Object2IntMap.Entry<Item> entry : cannon.checklist.required.object2IntEntrySet()) {
            if (cannon.checklist.gathered.getInt(entry.getKey()) < entry.getIntValue()) {
                return false;
            }
        }
        return true;
    }

    private void insertIntoInventory(final ItemStack stack) {
        ItemStack remainder = stack;
        for (int i = 0; i < inventory.getSlots() && !remainder.isEmpty(); i++) {
            remainder = inventory.insertItem(i, remainder, false);
        }
    }

    private int countItem(final Item item) {
        int count = 0;
        for (int i = 0; i < inventory.getSlots(); i++) {
            final ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty() && stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countItemInCluster(final Item item) {
        int count = countItem(item);
        final Level level = getLevel();
        if (level == null) {
            return count;
        }
        for (final Direction direction : Direction.values()) {
            final BlockEntity blockEntity = level.getBlockEntity(worldPosition.relative(direction));
            if (blockEntity instanceof AdvancedSchematicLoaderBlockEntity loader && loader != this) {
                count += loader.countItem(item);
            }
        }
        return count;
    }

    /** 供菜单同步：0/1/2/3 = 自动打印/部署/回收/火药，4 = 队列运行中。 */
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
    protected void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inventory", inventory.serializeNBT(registries));
        tag.put("Queue", queue.serializeNBT(registries));
        tag.put("Upgrades", upgradeContainer.serializeNBT(registries));
        tag.putBoolean("AutoPrint", autoPrint);
        tag.putBoolean("AutoDeploy", autoDeploy);
        tag.putBoolean("AutoRecycle", autoRecycle);
        tag.putBoolean("AutoGunpowder", autoFillGunpowder);
        tag.putBoolean("QueueRunning", queueRunning);
    }

    @Override
    protected void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Inventory")) {
            inventory.deserializeNBT(registries, tag.getCompound("Inventory"));
        }
        if (tag.contains("Queue")) {
            queue.deserializeNBT(registries, tag.getCompound("Queue"));
        }
        if (tag.contains("Upgrades")) {
            upgradeContainer.deserializeNBT(registries, tag.getCompound("Upgrades"));
        }
        autoPrint = tag.getBoolean("AutoPrint");
        autoDeploy = tag.getBoolean("AutoDeploy");
        autoRecycle = tag.getBoolean("AutoRecycle");
        autoFillGunpowder = tag.getBoolean("AutoGunpowder");
        queueRunning = tag.getBoolean("QueueRunning");
    }

    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            Capabilities.ItemHandler.BLOCK,
            RS_Create_Compat.ADVANCED_SCHEMATIC_LOADER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getInventory()
        );
        event.registerBlockEntity(
            RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.ADVANCED_SCHEMATIC_LOADER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
    }
}
