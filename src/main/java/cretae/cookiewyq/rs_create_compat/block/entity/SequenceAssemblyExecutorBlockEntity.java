package cretae.cookiewyq.rs_create_compat.block.entity;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSink;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.autocrafting.PatternProviderExternalPatternSink;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.autocrafting.Autocrafter;
import com.refinedmods.refinedstorage.common.api.support.network.InWorldNetworkNodeContainer;
import com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity;
import com.refinedmods.refinedstorage.common.support.network.ColoredConnectionStrategy;
import com.refinedmods.refinedstorage.common.support.network.InWorldNetworkNodeContainerImpl;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.item.SequenceAssemblyPatternItem;
import cretae.cookiewyq.rs_create_compat.network.SequenceAssemblyExecutorNetworkNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import java.util.Collection;

/**
 * 序列装配样板库（原“序列装配总控室”）：职责收敛为——
 * <ul>
 *     <li>槽 0 只存放一张「总样板」（序列装配样板）；</li>
 *     <li>方块实体作为网络里的“类自动合成仓”容器：节点是
 *     {@link com.refinedmods.refinedstorage.api.network.impl.node.patternprovider.PatternProviderNetworkNode}，
 *     总样板自动注册为 EXTERNAL 样板；</li>
 *     <li>从任意终端（样板终端 / 无线终端 / 合成网格）对总样板产物发起的就是原生自动合成，
 *     每轮迭代的主原料经 {@link #accept} 投给相邻 Create 产线入口（方块无任何“启动/停止”按钮）。</li>
 * </ul>
 */
public class SequenceAssemblyExecutorBlockEntity
    extends AbstractBaseNetworkNodeContainerBlockEntity<SequenceAssemblyExecutorNetworkNode>
    implements PatternProviderExternalPatternSink {
    /** 样板库格数（大型双箱规格 6×9）：存放多张总样板，每格一张，全部注册为 EXTERNAL 样板。 */
    public static final int PATTERN_SLOTS = 54;
    /**
     * 样板槽唯一判定：只收本模组的<b>序列装配总样板</b>（按物品类型，不看名字 / 文案）。
     *
     * <p><b>为什么它是 static 且必须被所有路径共用</b>：这个库是<b>总样板专用容器</b>
     * （总样板绑定机器 / 逐个步骤认领，必须能唯一定位「哪张是哪台机器的」），
     * 而单元样板是<b>不绑定执行仓</b>、想放哪就放哪的，只在这里被挡。
     * 三条路径全部经本判定收口：①界面真槽位（{@code SequenceAssemblyExecutorMenu.PatternSlot#mayPlace}）、
     * ②客户端镜像容器（同一判定的本地镜像，避免「客户端假装收下、点击又被服务端退回」）、
     * ③物流（漏斗 / 管道走 {@code canPlaceItem}）。</p>
     */
    public static boolean acceptsPattern(final ItemStack stack) {
        return SequenceAssemblyPatternItem.isAssemblyPattern(stack);
    }

    /** 槽 = 总样板（真实物品存放，一格一张）。同时作为 Autocrafter 管理器绑定与破坏掉落共用容器。 */
    private final SimpleContainer inventory = new SimpleContainer(PATTERN_SLOTS) {
        @Override
        public void setItem(final int index, final ItemStack stack) {
            super.setItem(index, stack);
            onPatternSlotChanged(index);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean canPlaceItem(final int index, final ItemStack stack) {
            // 物流路径（InvWrapper / VanillaContainerWrapper）的唯一闸门
            return acceptsPattern(stack);
        }
    };
    private boolean loadingInventory;

    public SequenceAssemblyExecutorBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.SEQUENCE_ASSEMBLY_EXECUTOR_BLOCK_ENTITY.get(), pos, state,
            new SequenceAssemblyExecutorNetworkNode());
        this.mainNetworkNode.setBlockEntity(this);
        this.mainNetworkNode.setSink(this);
        inventory.addListener(container -> {
            if (level != null && !level.isClientSide() && !loadingInventory) {
                refreshPatterns();
            }
        });
    }

    public SequenceAssemblyExecutorNetworkNode getNode() {
        return mainNetworkNode;
    }

    public Container getInventory() {
        return inventory;
    }

    public ItemStack getSample() {
        return inventory.getItem(0);
    }

    public boolean hasSample() {
        return !getSample().isEmpty();
    }

    // ========== 总样板 → 网络样板注册 ==========

    /** 每格总样板解析为 RS Pattern 并注册到对应节点样板位（null 则注销该位）。 */
    private void refreshPatterns() {
        for (int i = 0; i < PATTERN_SLOTS; i++) {
            final ItemStack stack = inventory.getItem(i);
            final Pattern pattern = stack.isEmpty() || level == null
                ? null
                : RefinedStorageApi.INSTANCE.getPattern(stack, level).orElse(null);
            mainNetworkNode.setPattern(i, pattern);
        }
    }

    private void onPatternSlotChanged(final int index) {
        setChanged();
        if (level != null && !level.isClientSide() && !loadingInventory) {
            final ItemStack stack = inventory.getItem(index);
            final Pattern pattern = stack.isEmpty()
                ? null
                : RefinedStorageApi.INSTANCE.getPattern(stack, level).orElse(null);
            mainNetworkNode.setPattern(index, pattern);
            if (pattern != null) {
                // 成就触发点：放进的这张总样板真的解析成了 RS Pattern（服务端；重复 fire 由原版去重）
                cretae.cookiewyq.rs_create_compat.advancement.RsccAdvancements.onAssemblyPatternLoaded(this);
            }
        }
    }

    @Override
    public void setLevel(final Level level) {
        super.setLevel(level);
        if (!level.isClientSide()) {
            refreshPatterns();
        }
    }

    // ========== 自动合成任务 sink：把每轮主原料放回网络（分布式：序列执行仓按步骤领取） ==========

    /**
     * 自动合成任务 sink：把每轮输入原样放回网络（分布式：序列执行仓按步骤领取）。
     * <p>{@link com.refinedmods.refinedstorage.api.autocrafting.PatternBuilder} 现在<b>同时登记物品与流体</b>
     * ingredient（见 {@code SequenceAssemblyPatternItem#buildPattern}），因此这里必须把两类资源都安全放回
     * 网络 —— 只处理物品会让 RS 从内部暂存里抽走流体却无人接收，等于销毁玩家资源。</p>
     */
    @Override
    public ExternalPatternSink.Result accept(final Collection<ResourceAmount> resources, final Action action) {
        final com.refinedmods.refinedstorage.api.network.Network network = mainNetworkNode.getNetworkOrNull();
        if (network == null) {
            return ExternalPatternSink.Result.SKIPPED;
        }
        final StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        boolean any = false;
        for (final ResourceAmount amount : resources) {
            final ResourceKey resource = amount.resource();
            if (!(resource instanceof ItemResource) && !(resource instanceof FluidResource)) {
                continue; // 其它资源类型不适用本机
            }
            final long count = Math.max(1, amount.amount());
            final long inserted = storage.insert(resource, count, action, Actor.EMPTY);
            if (inserted < count) {
                return ExternalPatternSink.Result.REJECTED; // 网络放不下全部则让任务稍后再试
            }
            any = true;
        }
        return any ? ExternalPatternSink.Result.ACCEPTED : ExternalPatternSink.Result.SKIPPED;
    }

    // ========== 使方块成为“类自动合成仓”（管理器可列出） ==========

    @Override
    protected InWorldNetworkNodeContainer createMainContainer(final SequenceAssemblyExecutorNetworkNode networkNode) {
        return new MasterPatternContainer(this, networkNode);
    }

    @Override
    public Component getName() {
        return getBlockState().getBlock().getName();
    }

    /** 供菜单同步：0 = 红石模式（0/1/2，与 RS 原版机器同一套编码，服务端权威）。 */
    public net.minecraft.world.inventory.ContainerData getContainerData() {
        return new net.minecraft.world.inventory.ContainerData() {
            @Override
            public int get(final int index) {
                if (index == 0) {
                    return com.refinedmods.refinedstorage.common.support.RedstoneModeSettings
                        .getRedstoneMode(getRedstoneMode());
                }
                return 0;
            }

            @Override
            public void set(final int index, final int value) {
                // 由服务端按钮逻辑修改
            }

            @Override
            public int getCount() {
                return 1;
            }
        };
    }

    // ========== 持久化 ==========

    @Override
    public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // 逐格带 "Slot"（原版 SimpleContainer#createTag/fromTag 会丢槽位、把多张样板挤成一格）
        tag.put("Inventory", cretae.cookiewyq.rs_create_compat.support.RsccSlotNbt.write(inventory, registries));
    }

    @Override
    public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        loadingInventory = true;
        super.loadAdditional(tag, registries);
        if (tag.contains("Inventory")) {
            cretae.cookiewyq.rs_create_compat.support.RsccSlotNbt.read(
                tag.getList("Inventory", Tag.TAG_COMPOUND), inventory, registries);
        }
        loadingInventory = false;
        refreshPatterns();
    }

    /** 注册方块能力：RS 网络节点容器。 */
    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.SEQUENCE_ASSEMBLY_EXECUTOR_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
    }

    /** “类自动合成仓”容器：使样板库出现在原版/虚拟自动合成仓管理器中，可被任意终端发现。 */
    private static final class MasterPatternContainer extends InWorldNetworkNodeContainerImpl implements Autocrafter {
        private final SequenceAssemblyExecutorBlockEntity blockEntity;

        private MasterPatternContainer(final SequenceAssemblyExecutorBlockEntity blockEntity,
                                       final com.refinedmods.refinedstorage.api.network.node.NetworkNode node) {
            super(blockEntity, node, "main", 0,
                new ColoredConnectionStrategy(blockEntity::getBlockState, blockEntity.getBlockPos()), null);
            this.blockEntity = blockEntity;
        }

        @Override
        public Component getAutocrafterName() {
            return blockEntity.getName();
        }

        @Override
        public Container getPatternContainer() {
            return blockEntity.inventory;
        }

        @Override
        public boolean isVisibleToTheAutocrafterManager() {
            return true;
        }
    }
}
