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
import cretae.cookiewyq.rs_create_compat.network.SchematicLoaderNetworkNode;
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
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.ItemStackHandler;

import javax.annotation.Nullable;

/**
 * 蓝图加农炮装填器（基础版）：自动从 RS 网络获取相邻 Create 蓝图加农炮（Schematicannon）所需资源，
 * 自身作为加农炮的合法容器（IItemHandler）供其提取。
 * <p>
 * 高级蓝图加农炮装填器继承本类并扩展队列功能（见子类）。
 */
public class SchematicLoaderBlockEntity extends AbstractBaseNetworkNodeContainerBlockEntity<SchematicLoaderNetworkNode> {
    protected static final ResourceLocation SPEED_UPGRADE =
        ResourceLocation.fromNamespaceAndPath("refinedstorage", "speed_upgrade");
    protected static final ResourceLocation AUTOCRAFTING_UPGRADE =
        ResourceLocation.fromNamespaceAndPath("refinedstorage", "autocrafting_upgrade");

    /** 主库存（基础版 54 格，高级版 108 格）。 */
    protected final ItemStackHandler inventory;
    /** 蓝图槽（无加农炮时手动放置已部署蓝图）。 */
    protected final ItemStackHandler blueprintSlot = new ItemStackHandler(1);
    /** 插件槽：6 格。 */
    protected final ItemStackHandler upgradeContainer = new ItemStackHandler(6);
    /** 蓝图队列（仅高级版启用）。 */
    protected final ItemStackHandler queue;

    protected boolean autoPrint;
    protected boolean autoDeploy;
    protected boolean autoRecycle;
    protected boolean autoFillGunpowder = true;

    public SchematicLoaderBlockEntity(final BlockPos pos, final BlockState state) {
        this(RS_Create_Compat.SCHEMATIC_LOADER_BLOCK_ENTITY.get(), pos, state, 54, false);
    }

    protected SchematicLoaderBlockEntity(final BlockEntityType<?> blockEntityType,
                                         final BlockPos pos,
                                         final BlockState state,
                                         final int capacity,
                                         final boolean withQueue) {
        super(blockEntityType, pos, state, new SchematicLoaderNetworkNode());
        this.inventory = new ItemStackHandler(capacity);
        this.queue = withQueue ? new ItemStackHandler(27) : new ItemStackHandler(0);
        this.mainNetworkNode.setBlockEntity(this);
    }

    public ItemStackHandler getInventory() {
        return inventory;
    }

    public ItemStackHandler getBlueprintSlot() {
        return blueprintSlot;
    }

    public ItemStackHandler getUpgradeContainer() {
        return upgradeContainer;
    }

    /** 返回网络节点（主要用于方块被破坏时读取 Network 引用以回流物品）。 */
    public SchematicLoaderNetworkNode getNode() {
        return mainNetworkNode;
    }

    /** 蓝图队列（高级版 27 格；基础版为空占位，供菜单统一引用）。 */
    public ItemStackHandler getQueue() {
        return queue;
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

    public long getEnergyUsage() {
        return 10;
    }

    protected boolean hasAutocraftingUpgrade() {
        return countUpgrades(AUTOCRAFTING_UPGRADE) > 0;
    }

    protected int countUpgrades(final ResourceLocation upgradeId) {
        final Item upgradeItem = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(upgradeId);
        int count = 0;
        for (int i = 0; i < upgradeContainer.getSlots(); i++) {
            final ItemStack stack = upgradeContainer.getStackInSlot(i);
            if (!stack.isEmpty() && stack.is(upgradeItem)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    @Override
    protected boolean hasRedstoneMode() {
        return false;
    }

    @Override
    public net.minecraft.network.chat.Component getName() {
        return getBlockState().getBlock().getName();
    }

    /** 取出下一张待打印蓝图（基础版从蓝图槽取；高级版覆写从队列取）。 */
    protected ItemStack getNextBlueprint() {
        final ItemStack stack = blueprintSlot.getStackInSlot(0);
        if (!stack.isEmpty()) {
            blueprintSlot.setStackInSlot(0, ItemStack.EMPTY);
        }
        return stack;
    }

    /** 由网络节点每 tick 驱动：为相邻蓝图加农炮补充资源。 */
    public void doLoaderWork(final Network network) {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        network.getComponent(EnergyNetworkComponent.class).extract(getEnergyUsage());

        final SchematicannonBlockEntity cannon = findAttachedCannon(level);
        if (cannon == null) {
            return; // 没有紧贴的加农炮，仅保留手动蓝图槽
        }

        // 自动部署：将下一张蓝图放入加农炮蓝图槽
        boolean blueprintChanged = false;
        if (autoDeploy && cannon.inventory.getStackInSlot(0).isEmpty()) {
            final ItemStack blueprint = getNextBlueprint();
            if (!blueprint.isEmpty()) {
                cannon.inventory.setStackInSlot(0, blueprint);
                cannon.sendUpdate = true;
                blueprintChanged = true;
                // 强制打印机重载新蓝图，否则 checklist 仍用旧蓝图的材料清单
                cannon.printer.resetSchematic();
            }
        }

        // 蓝图更换后强制刷新 checklist，避免沿用旧蓝图的材料清单
        cannon.updateChecklist();
        restockFromNetwork(cannon, network);

        if (autoFillGunpowder) {
            restockGunpowderDirect(cannon, network);
        }
        // 自动回收：打印完成后回收空白蓝图（基础版放回蓝图槽，高级版放入队列）
        if (isAutoRecycleActive() && cannon.blocksToPlace > 0 && cannon.blocksPlaced >= cannon.blocksToPlace) {
            final ItemStack out = cannon.inventory.getStackInSlot(1);
            if (!out.isEmpty()) {
                recycleFinishedBlueprint(out.copy());
                cannon.inventory.setStackInSlot(1, ItemStack.EMPTY);
                cannon.sendUpdate = true;
            }
        }

        // 自动打印：资源足够时触发加农炮开始打印
        if (shouldAutoPrint() && cannon.state == State.STOPPED && cannon.printer.isLoaded() && isResourcesReady(cannon)) {
            cannon.state = State.RUNNING;
            cannon.sendUpdate = true;
        }
    }

    /** 是否启用自动回收（高级版队列运行时可覆写）。 */
    protected boolean isAutoRecycleActive() {
        return autoRecycle;
    }

    /** 处理打印完成的空白蓝图（高级版覆写放入队列）。 */
    protected void recycleFinishedBlueprint(final ItemStack out) {
        blueprintSlot.setStackInSlot(0, out);
    }

    /** 是否触发自动打印（高级版队列运行时可覆写）。 */
    protected boolean shouldAutoPrint() {
        return autoPrint;
    }

    protected SchematicannonBlockEntity findAttachedCannon(final Level level) {
        for (final Direction direction : Direction.values()) {
            final BlockEntity blockEntity = level.getBlockEntity(worldPosition.relative(direction));
            if (blockEntity instanceof SchematicannonBlockEntity cannon) {
                return cannon;
            }
        }
        return null;
    }

    protected void restockFromNetwork(final SchematicannonBlockEntity cannon, final Network network) {
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        final AutocraftingNetworkComponent autocrafting = network.getComponent(AutocraftingNetworkComponent.class);
        // 以加农炮为中心 BFS 统计所有紧贴装填器（含自身）中该物品的已有量，
        // 避免多个装填器分别贴加农炮两侧互不识别导致重复拉取。
        for (final Object2IntMap.Entry<Item> entry : cannon.checklist.required.object2IntEntrySet()) {
            final Item item = entry.getKey();
            final int needed = entry.getIntValue();
            final int clusterHave = countItemInClusterAroundCannon(cannon, item);
            final int toFetch = needed - clusterHave;
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
                // 网络不足且安装了自动合成升级：请求自动合成
                autocrafting.ensureTask(resource, toFetch - inNetwork, Actor.EMPTY, null);
            }
        }
        // 打印完成后，库存中剩余的蓝图材料自动回流网络（不再多留）
        recycleOverflowToNetwork(cannon, network);
    }

    /** 打印完成后，库存中属于当前蓝图的材料自动回流到网络。 */
    private void recycleOverflowToNetwork(final SchematicannonBlockEntity cannon, final Network network) {
        // 仅在打印完成（或没有进行中的打印任务）时清理，避免打印过程中误回收
        final boolean printingDone = cannon.blocksToPlace > 0 && cannon.blocksPlaced >= cannon.blocksToPlace;
        if (!printingDone) {
            return;
        }
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        for (int i = 0; i < inventory.getSlots(); i++) {
            final ItemStack stack = inventory.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (cannon.checklist.required.getInt(stack.getItem()) <= 0) {
                continue; // 非蓝图材料不回流
            }
            final ItemStack toReturn = inventory.extractItem(i, stack.getCount(), false);
            if (!toReturn.isEmpty()) {
                final ResourceKey resource = new ItemResource(toReturn.getItem(), DataComponentPatch.EMPTY);
                storage.insert(resource, toReturn.getCount(), Action.EXECUTE, Actor.EMPTY);
            }
        }
    }

    /** 火药直装蓝图加农炮的火药槽（slot 4），不回流装填器库存。 */
    protected void restockGunpowderDirect(final SchematicannonBlockEntity cannon, final Network network) {
        final ItemStack inCannon = cannon.inventory.getStackInSlot(4);
        final int current = inCannon.isEmpty() ? 0 : inCannon.getCount();
        if (current >= 64) {
            return;
        }
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        final ResourceKey resource = new ItemResource(Items.GUNPOWDER, DataComponentPatch.EMPTY);
        final long inNetwork = storage.get(resource);
        if (inNetwork <= 0) {
            return;
        }
        final long toFetch = Math.min(inNetwork, 64 - current);
        final long extracted = storage.extract(resource, toFetch, Action.EXECUTE, Actor.EMPTY);
        if (extracted > 0) {
            final int total = current + (int) extracted;
            cannon.inventory.setStackInSlot(4, new ItemStack(Items.GUNPOWDER, total));
            cannon.sendUpdate = true;
        }
    }

    protected boolean isResourcesReady(final SchematicannonBlockEntity cannon) {
        for (final Object2IntMap.Entry<Item> entry : cannon.checklist.required.object2IntEntrySet()) {
            final int gathered = cannon.checklist.gathered.getInt(entry.getKey());
            if (gathered < entry.getIntValue()) {
                return false;
            }
        }
        return true;
    }

    protected void insertIntoInventory(final ItemStack stack) {
        ItemStack remainder = stack;
        for (int i = 0; i < inventory.getSlots() && !remainder.isEmpty(); i++) {
            remainder = inventory.insertItem(i, remainder, false);
        }
    }

    protected int countItem(final Item item) {
        int count = 0;
        for (int i = 0; i < inventory.getSlots(); i++) {
            final ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty() && stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** 以加农炮为中心，统计所有紧贴它的装填器（含自身）中的该物品数量，避免多装填器互不识别。 */
    protected int countItemInClusterAroundCannon(final SchematicannonBlockEntity cannon, final Item item) {
        int count = 0;
        final Level level = getLevel();
        if (level == null) {
            return count;
        }
        final java.util.IdentityHashMap<SchematicLoaderBlockEntity, Boolean> seen =
            new java.util.IdentityHashMap<>();
        final java.util.Queue<SchematicLoaderBlockEntity> queue = new java.util.ArrayDeque<>();
        // 起点：加农炮六个方向上所有的装填器
        for (final Direction d : Direction.values()) {
            final BlockEntity be = level.getBlockEntity(cannon.getBlockPos().relative(d));
            if (be instanceof SchematicLoaderBlockEntity ldr && !seen.containsKey(ldr)) {
                seen.put(ldr, Boolean.TRUE);
                queue.add(ldr);
            }
        }
        while (!queue.isEmpty()) {
            final SchematicLoaderBlockEntity ldr = queue.poll();
            count += ldr.countItem(item);
            // BFS 展开邻接装填器（装填器贴在一起时也算同一集群）
            for (final Direction d : Direction.values()) {
                final BlockEntity be = level.getBlockEntity(ldr.getBlockPos().relative(d));
                if (be instanceof SchematicLoaderBlockEntity next && !seen.containsKey(next)) {
                    seen.put(next, Boolean.TRUE);
                    queue.add(next);
                }
            }
        }
        return count;
    }

    /** 统计所有紧贴连接的装填器（含自身）中该物品的数量，避免重复拉取。 */
    protected int countItemInCluster(final Item item) {
        int count = countItem(item);
        final Level level = getLevel();
        if (level == null) {
            return count;
        }
        for (final Direction direction : Direction.values()) {
            final BlockEntity blockEntity = level.getBlockEntity(worldPosition.relative(direction));
            if (blockEntity instanceof SchematicLoaderBlockEntity loader && loader != this) {
                count += loader.countItem(item);
            }
        }
        return count;
    }

    /**
     * 收集与自身紧贴相连的<b>同类型</b>装填器集群（BFS）。
     * 基础装填器只与基础装填器合并，高级装填器只与高级装填器合并（通过 isAdvanced 区分），
     * 基础与高级之间不互相合并。
     *
     * @return 集群内所有装填器（含自身），按 BFS 顺序
     */
    public java.util.List<SchematicLoaderBlockEntity> collectCluster() {
        final java.util.List<SchematicLoaderBlockEntity> result = new java.util.ArrayList<>();
        final Level level = getLevel();
        if (level == null) {
            result.add(this);
            return result;
        }
        final boolean selfAdvanced = this instanceof AdvancedSchematicLoaderBlockEntity;
        final java.util.IdentityHashMap<SchematicLoaderBlockEntity, Boolean> seen =
            new java.util.IdentityHashMap<>();
        final java.util.Queue<SchematicLoaderBlockEntity> queue = new java.util.ArrayDeque<>();
        seen.put(this, Boolean.TRUE);
        queue.add(this);
        while (!queue.isEmpty()) {
            final SchematicLoaderBlockEntity ldr = queue.poll();
            result.add(ldr);
            for (final Direction direction : Direction.values()) {
                final BlockEntity neighbor = level.getBlockEntity(ldr.worldPosition.relative(direction));
                if (neighbor instanceof SchematicLoaderBlockEntity next
                    && !seen.containsKey(next)
                    && (next instanceof AdvancedSchematicLoaderBlockEntity) == selfAdvanced) {
                    seen.put(next, Boolean.TRUE);
                    queue.add(next);
                }
            }
        }
        return result;
    }

    /** 供菜单同步：0/1/2/3 = 自动打印/部署/回收/火药 开关，4 = 集群装填器数量。 */
    public net.minecraft.world.inventory.ContainerData getContainerData() {
        return new net.minecraft.world.inventory.ContainerData() {
            @Override
            public int get(final int index) {
                return switch (index) {
                    case 0 -> autoPrint ? 1 : 0;
                    case 1 -> autoDeploy ? 1 : 0;
                    case 2 -> autoRecycle ? 1 : 0;
                    case 3 -> autoFillGunpowder ? 1 : 0;
                    case 4 -> collectCluster().size();
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
        tag.put("Inventory", inventory.serializeNBT(registries));
        tag.put("Blueprint", blueprintSlot.serializeNBT(registries));
        tag.put("Upgrades", upgradeContainer.serializeNBT(registries));
        if (queue.getSlots() > 0) {
            tag.put("Queue", queue.serializeNBT(registries));
        }
        tag.putBoolean("AutoPrint", autoPrint);
        tag.putBoolean("AutoDeploy", autoDeploy);
        tag.putBoolean("AutoRecycle", autoRecycle);
        tag.putBoolean("AutoGunpowder", autoFillGunpowder);
    }

    @Override
    public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Inventory")) {
            inventory.deserializeNBT(registries, tag.getCompound("Inventory"));
        }
        if (tag.contains("Blueprint")) {
            blueprintSlot.deserializeNBT(registries, tag.getCompound("Blueprint"));
        }
        if (tag.contains("Upgrades")) {
            upgradeContainer.deserializeNBT(registries, tag.getCompound("Upgrades"));
        }
        if (queue.getSlots() > 0 && tag.contains("Queue")) {
            queue.deserializeNBT(registries, tag.getCompound("Queue"));
        }
        autoPrint = tag.getBoolean("AutoPrint");
        autoDeploy = tag.getBoolean("AutoDeploy");
        autoRecycle = tag.getBoolean("AutoRecycle");
        autoFillGunpowder = tag.getBoolean("AutoGunpowder");
    }

    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            Capabilities.ItemHandler.BLOCK,
            RS_Create_Compat.SCHEMATIC_LOADER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getInventory()
        );
        event.registerBlockEntity(
            RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.SCHEMATIC_LOADER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
    }
}
