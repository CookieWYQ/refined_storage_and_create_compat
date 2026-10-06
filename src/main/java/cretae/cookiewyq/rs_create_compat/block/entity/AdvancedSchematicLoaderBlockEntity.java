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

    /** 点击开始/停止：切换队列自动打印（集群内所有高级装填器同步）。 */
    public void toggleQueue() {
        this.queueRunning = !this.queueRunning;
        for (final SchematicLoaderBlockEntity loader : collectCluster()) {
            if (loader == this) {
                continue;
            }
            if (loader instanceof AdvancedSchematicLoaderBlockEntity advanced) {
                advanced.queueRunning = this.queueRunning;
            }
            // 集群内其余成员的本轮收集状态一并复位：真正紧贴加农炮的那台可能不是玩家操作的那台
            loader.resetRoundCollection();
            loader.setChanged();
        }
        setChanged();
        // 「开始」= 进入新一轮收集（即使还是同一张蓝图也重新收集一遍）；
        // 「停止」= 清掉本轮的收集/自动合成请求状态，下一 tick 起立即不再向网络拉取。
        resetRoundCollection();
    }

    /**
     * 当前蓝图：队列中第一个可打印蓝图（跳过空槽、空白蓝图与其它非蓝图杂物）。
     * 用于无加农炮时的材料拉取。
     */
    @Override
    protected ItemStack getNextBlueprint() {
        for (int i = 0; i < queue.getSlots(); i++) {
            final ItemStack stack = queue.getStackInSlot(i);
            if (isDeployableQueueEntry(stack)) {
                return stack.copy();
            }
        }
        return super.getNextBlueprint();
    }

    /** 队列槽里是否是可以部署打印的蓝图（空白蓝图/杂物跳过）。 */
    private static boolean isDeployableQueueEntry(final ItemStack stack) {
        return !stack.isEmpty()
            && !stack.is(com.simibubi.create.AllItems.EMPTY_SCHEMATIC.get())
            && SchematicLoaderBlockEntity.isValidBlueprintStack(stack);
    }

    /** 队列流水线：取走队列中第一个可打印蓝图（跳过空白蓝图/杂物），用于部署到加农炮。 */
    @Override
    protected ItemStack takeFromQueue() {
        for (int i = 0; i < queue.getSlots(); i++) {
            final ItemStack stack = queue.getStackInSlot(i);
            if (isDeployableQueueEntry(stack)) {
                final ItemStack taken = stack.copy();
                queue.setStackInSlot(i, ItemStack.EMPTY);
                return taken;
            }
        }
        return ItemStack.EMPTY;
    }

    /** 队列运行中时自动部署下一张蓝图（打印完成后推进流水线）。 */
    @Override
    protected boolean shouldDeployNext() {
        return queueRunning;
    }

    /** 自动打印：<b>只由「自动打印」开关决定</b>。
     *  <p>START/STOP 只管「收集资源 + 推进队列」，<b>绝不代按打印</b> ——
     *  旧实现 {@code autoPrint || queueRunning} 让「开始」绕过开关强制打印，
     *  于是玩家把自动打印关掉后机器依然自己开打（用户报告的问题）。 */
    @Override
    protected boolean shouldAutoPrint() {
        return autoPrint;
    }

    /** 收集门控：只有队列运行中才向网络拉取。
     *  <p>「开始」= 开始为本轮蓝图收集资源；「停止」= <b>立即</b>停止拉取（已拉进库存的材料原样留着）。 */
    @Override
    protected boolean isCollectionEnabled() {
        return queueRunning;
    }

    /** 队列里是否还有下一张待打印蓝图（P1 自动停止判定用）。 */
    @Override
    protected boolean hasPendingBlueprintQueue() {
        for (int i = 0; i < queue.getSlots(); i++) {
            if (isDeployableQueueEntry(queue.getStackInSlot(i))) {
                return true;
            }
        }
        return false;
    }

    /** P1：队列无蓝图可打时，把"停止/开始"运行状态自动切回"开始"（含集群内其它高级装填器）。 */
    @Override
    protected void stopQueueRun() {
        if (!queueRunning) {
            return;
        }
        this.queueRunning = false;
        for (final SchematicLoaderBlockEntity loader : collectCluster()) {
            if (loader == this) {
                continue;
            }
            if (loader instanceof AdvancedSchematicLoaderBlockEntity advanced) {
                advanced.queueRunning = false;
            }
            // 运行状态回落 → 收集也随之结束：清掉残留的请求状态，避免继续向网络要料
            loader.resetRoundCollection();
            loader.setChanged();
        }
        setChanged();
        resetRoundCollection();
        logState("queue", "auto stopped: no blueprint left to print");
    }

    /** 每个 work tick：把队列里混入的非蓝图杂物弹出（P3；含 NBT 遗留或旧档数据）。 */
    @Override
    protected void onLoaderWorkTick() {
        if (level == null || level.isClientSide()) {
            return;
        }
        for (int i = 0; i < queue.getSlots(); i++) {
            final ItemStack stack = queue.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            // 空白蓝图保留（旧逻辑允许跳过），其余非蓝图杂物直接弹出
            if (!stack.is(com.simibubi.create.AllItems.EMPTY_SCHEMATIC.get())
                && !SchematicLoaderBlockEntity.isValidBlueprintStack(stack)) {
                logState("queue", "ejected non-blueprint from queue slot " + i + ": "
                    + stack.getHoverName().getString());
                queue.setStackInSlot(i, ItemStack.EMPTY);
                setChanged();
                dropStack(stack);
            }
        }
    }

    /** 供菜单同步：0/1/2 = 自动打印/回收/火药 开关，3 = 队列运行中，4 = 集群内存总行数，5 = 红石模式，
     *  6 = 集群队列总行数（高级↔高级共享后队列叠加，界面按它设滚动范围）。 */
    @Override
    public net.minecraft.world.inventory.ContainerData getContainerData() {
        return new net.minecraft.world.inventory.ContainerData() {
            @Override
            public int get(final int index) {
                return switch (index) {
                    case 0 -> autoPrint ? 1 : 0;
                    case 1 -> autoRecycle ? 1 : 0;
                    case 2 -> autoFillGunpowder ? 1 : 0;
                    case 3 -> queueRunning ? 1 : 0;
                    case 4 -> getClusterTotalRows();
                    // 红石模式：走 RS 的 RedstoneModeSettings 映射（与 RS 原版机器同一套 0/1/2 编码）
                    case 5 -> com.refinedmods.refinedstorage.common.support.RedstoneModeSettings
                        .getRedstoneMode(getRedstoneMode());
                    case 6 -> getQueueSlotTotal() / 9;
                    default -> 0;
                };
            }

            @Override
            public void set(final int index, final int value) {
                // 由服务端按钮逻辑修改
            }

            @Override
            public int getCount() {
                return 7;
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
