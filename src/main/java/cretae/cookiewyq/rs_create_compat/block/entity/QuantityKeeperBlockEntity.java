package cretae.cookiewyq.rs_create_compat.block.entity;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.network.QuantityKeeperNetworkNode;
import cretae.cookiewyq.rs_create_compat.support.KeeperOverflow;
import cretae.cookiewyq.rs_create_compat.support.KeeperTarget;
import cretae.cookiewyq.rs_create_compat.support.MarkerEntry;
import cretae.cookiewyq.rs_create_compat.support.MultiFluidCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * 定量保持器：标记一个对象（物品 / 显式流体 / 显式气体），
 * 数量不足时自动触发 RS 自动合成（需自动合成升级），超出时可自动销毁过量部分。
 * 拥有 6 个插件槽（速度升级加快销毁、自动合成升级启用合成）。
 * <p>同类存储只接受与标记同种的资源：物品形态标记（含容器物品，如水桶）一律按物品处理，
 * 只有显式流体/气体标记才走流体路径。</p>
 */
public class QuantityKeeperBlockEntity extends AbstractBaseNetworkNodeContainerBlockEntity<QuantityKeeperNetworkNode> {
    /** 诊断日志：插件槽异常只读校验的结果（不刷屏，只在异常组合变化时打一条）。 */
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final ResourceLocation SPEED_UPGRADE = ResourceLocation.fromNamespaceAndPath("refinedstorage", "speed_upgrade");
    private static final ResourceLocation STACK_UPGRADE = ResourceLocation.fromNamespaceAndPath("refinedstorage", "stack_upgrade");
    private static final ResourceLocation AUTOCRAFTING_UPGRADE = ResourceLocation.fromNamespaceAndPath("refinedstorage", "autocrafting_upgrade");

    /** 标记形态：物品（用标记槽里的物品）。 */
    public static final int FORM_ITEM = 0;
    /** 标记形态：流体。 */
    public static final int FORM_FLUID = 1;
    /** 标记形态：气体（Mekanism 化学品同样以流体标识承载）。 */
    public static final int FORM_GAS = 2;

    /** 同类存储：物品格数（只接受被标记的同一种物品）。 */
    public static final int STORAGE_SLOTS = 27;
    /** 同类存储：单格上限。 */
    public static final int STORAGE_SLOT_LIMIT = 64;
    /** 同类存储：流体/气体总容量（mB，128 桶）。 */
    public static final long FLUID_STORAGE_CAPACITY = 128_000L;

    private static final String TAG_FLUID_MARKER_ID = "FluidMarkerId";
    private static final String TAG_FLUID_MARKER_NBT = "FluidMarkerNbt";
    private static final String TAG_MARKER_FORM = "MarkerForm";
    private static final String TAG_STORAGE = "Storage";
    private static final String TAG_FLUID_STORAGE = "FluidStorage";

    /** 标记槽（第 0 格） + 插件槽（6 格）。 */
    private final SimpleContainer inventory = new SimpleContainer(7);

    // ===== 直接标记的流体/气体（任务 2，独立于「容器物品反推」路径）=====
    /** 直接标记的流体/气体注册名（null = 无直接流体标记）。 */
    @Nullable
    private ResourceLocation fluidMarkerId;
    /** 直接标记的流体/气体数据组件 NBT（不透明负载，原样往返）。 */
    private CompoundTag fluidMarkerNbt = new CompoundTag();
    /** 当前标记形态：{@link #FORM_ITEM} / {@link #FORM_FLUID} / {@link #FORM_GAS}。 */
    private int markerForm = FORM_ITEM;

    // ===== 同类存储（任务 3）：只存放与标记同一种资源 =====
    /** 物品同类存储：只接受被标记的那一种物品（不匹配时 {@code insertItem} 原样返回 = 堵塞）。 */
    private final ItemStackHandler storage = new ItemStackHandler(STORAGE_SLOTS) {
        @Override
        public boolean isItemValid(final int slot, final ItemStack stack) {
            return matchesItemMarker(stack);
        }

        @Override
        protected int getStackLimit(final int slot, final ItemStack stack) {
            return Math.min(STORAGE_SLOT_LIMIT, stack.getMaxStackSize());
        }

        @Override
        protected void onContentsChanged(final int slot) {
            setChanged();
            updateBlockedState();
        }

        /**
         * 兼容旧存档的格子数：{@code ItemStackHandler#deserializeNBT} 会把尺寸重置为 NBT 里记录的
         * 「Size」（旧版为 9）。读完后统一补齐到 {@link #STORAGE_SLOTS} 并保留已读内容，
         * 避免容量升级对旧存档不生效（绝不截断已有内容）。
         */
        @Override
        public void deserializeNBT(final HolderLookup.Provider registries, final CompoundTag nbt) {
            super.deserializeNBT(registries, nbt);
            if (getSlots() >= STORAGE_SLOTS) {
                return;
            }
            final List<ItemStack> loaded = new java.util.ArrayList<>(getSlots());
            for (int i = 0; i < getSlots(); i++) {
                loaded.add(getStackInSlot(i));
            }
            setSize(STORAGE_SLOTS);
            for (int i = 0; i < loaded.size(); i++) {
                if (!loaded.get(i).isEmpty()) {
                    setStackInSlot(i, loaded.get(i));
                }
            }
        }
    };
    /** 流体/气体同类存储：只会接受被标记的那一种流体。 */
    private final MultiFluidCache fluidStorage = new MultiFluidCache(FLUID_STORAGE_CAPACITY);
    /** 流体能力的外观实现（惰性创建）。 */
    private IFluidHandler fluidHandler;
    /** 物品能力的外观实现（惰性创建）。 */
    private net.neoforged.neoforge.items.IItemHandler itemHandler;
    /** 是否处于「堵塞」状态（内部存在与当前标记不匹配的资源）：堵塞时停止输出，但资源仍可被取出。 */
    private boolean blocked;
    /** 状态是否已随掉落物 NBT 带走（战利品表已调用 {@link #saveStateForItem()}）：避免创造模式拆除时漏掉。 */
    private boolean stateDropped;

    private int targetAmount = Config.quantityKeeperDefaultTarget;
    private boolean destroyOverflow = true;
    /**
     * 自动合成开关：仅在装有自动合成升级时有效（界面据此启用开关）。
     *
     * <p><b>默认关闭</b>：崭新的机器、以及「新插入自动合成升级」这两个时刻都<b>不会</b>把开关打开
     * ——「装了升级」只代表<b>能力可用</b>（按钮可点），是否合成必须由玩家在界面上手动开启。
     * 这里刻意只改「新机器的初值」：已有存档里已经打开的开关由 NBT（{@code AutoCraftEnabled}）
     * 原样读回，<b>绝不强制关掉</b>（见 {@link #loadAdditional}）。</p>
     */
    private boolean autoCraftEnabled = false;
    /** 从 NBT 载入库存期间跳过即时校验，避免在 level 尚未就绪时丢物品。 */
    private boolean loadingInventory;
    /** 「插件槽异常」诊断的节流签名：只在异常组合变化时打一条日志，绝不刷屏（只读，不改物品）。 */
    private String lastUpgradeAnomaly = "";
    /** 「过量销毁」的诊断记账（服务端权威；只记账 + 打日志，不做任何搬运）。 */
    private final KeeperOverflow.Episode overflowLog = new KeeperOverflow.Episode();

    public QuantityKeeperBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.QUANTITY_KEEPER_BLOCK_ENTITY.get(), pos, state, new QuantityKeeperNetworkNode());
        this.mainNetworkNode.setBlockEntity(this);
        // 容量变化时刷新节点状态 + 即时校验插件槽（约束不落空）
        inventory.addListener(container -> {
            if (level != null && !level.isClientSide() && !loadingInventory) {
                enforceUpgradeCaps();
            }
            setChanged();
        });
    }

    /**
     * 插件槽校验：<b>纯只读 —— 绝不搬移 / 拆分 / 弹出 / 删除任何物品</b>。
     *
     * <p><b>为什么从「即时弹出」改成只读（守恒硬底线）</b>：旧实现的
     * {@code ejectSlot} 会先把物品 {@code removeItemNoUpdate(index)} 从容器里删掉、再生成掉落实体；
     * 这条「先删后给」不是原子操作，一旦「给」的那一步落空（掉落实体被生成在方块<b>自己所在的实心
     * 方块内部</b>而被埋 / 实体随后 despawn），物品就永久离开玩家的世界（高级版实测「插件直接就没了」，
     * 基础版共用同一套写法，因此一并改成只读）。</p>
     *
     * <p>现在这里<b>只判定</b>并打一条节流日志，一个物品都不动：已经存在的「非法 / 超上限」状态原样保留。
     * 「不再出现」的最小防线在界面层 {@code UpgradeSlot.mayPlace}（拒收非空槽）+ {@code getMaxStackSize()==1}，
     * 两者都<b>只拒绝放入</b>，物品完整留在原处。</p>
     */
    public void enforceUpgradeCaps() {
        if (level == null || level.isClientSide() || loadingInventory) {
            return;
        }
        int invalid = 0;
        final int[] perKind = new int[3]; // 0=speed 1=stack 2=autocraft
        for (int i = 1; i < inventory.getContainerSize(); i++) {
            final ItemStack inSlot = inventory.getItem(i);
            if (inSlot.isEmpty()) {
                continue;
            }
            final int kind = upgradeKind(inSlot);
            if (kind < 0) {
                invalid++;
                continue;
            }
            perKind[kind]++;
        }
        final boolean anomalous = invalid > 0 || perKind[0] > 6 || perKind[1] > 6 || perKind[2] > 1;
        final String signature = anomalous
            ? invalid + "/" + perKind[0] + "/" + perKind[1] + "/" + perKind[2] : "";
        if (!signature.equals(lastUpgradeAnomaly)) {
            lastUpgradeAnomaly = signature;
            if (anomalous) {
                LOGGER.info("[{}] 插件槽异常状态被保留原样（非法物品 / 超上限）：invalid={} speed={} stack={}"
                    + " autocraft={} —— 只读校验：不搬移、不拆分、不弹出、不删除",
                    worldPosition, invalid, perKind[0], perKind[1], perKind[2]);
            }
        }
    }

    /** 0=speed，1=stack，2=autocraft，其余 -1。 */
    private static int upgradeKind(final ItemStack stack) {
        final ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null || !"refinedstorage".equals(id.getNamespace())) {
            return -1;
        }
        return switch (id.getPath()) {
            case "speed_upgrade" -> 0;
            case "stack_upgrade" -> 1;
            case "autocrafting_upgrade" -> 2;
            default -> -1;
        };
    }

    public QuantityKeeperNetworkNode getNode() {
        return mainNetworkNode;
    }

    public SimpleContainer getInventory() {
        return inventory;
    }

    public ItemStack getMarkerStack() {
        return inventory.getItem(0);
    }

    // ==================== 标记（物品 / 流体 / 气体） ====================

    /** 当前标记形态：{@link #FORM_ITEM} / {@link #FORM_FLUID} / {@link #FORM_GAS}。 */
    public int getMarkerForm() {
        return markerForm;
    }

    /** 直接标记的流体/气体注册名（无直接标记返回 null）。 */
    @Nullable
    public ResourceLocation getFluidMarkerId() {
        return fluidMarkerId;
    }

    /** 直接标记的流体/气体数据组件 NBT（无标记返回空 tag）。 */
    public CompoundTag getFluidMarkerNbt() {
        return fluidMarkerNbt == null ? new CompoundTag() : fluidMarkerNbt;
    }

    /** 是否存在「直接标记」的流体/气体（任务 2 的新路径）。 */
    public boolean hasDirectFluidMarker() {
        return markerForm != FORM_ITEM && fluidMarkerId != null;
    }

    /**
     * 标记是否为流体（含气体）：<b>仅当存在「显式标记」时</b>为真（form=1/2 且带流体 id，
     * 即 JEI 拖入的 {@code FluidStack} 或未来的其它显式流体入口）。
     * <p>物品形态标记一律按物品处理：即使该物品是装有流体的容器（水桶 / 岩浆桶 / 瓶子…），
     * 也<b>不再</b>从容器物品反推流体。</p>
     */
    public boolean isFluidMarker() {
        return hasDirectFluidMarker();
    }

    /** 标记中的流体/气体（仅显式标记时有效）；物品形态标记一律返回 {@link FluidStack#EMPTY}。 */
    public FluidStack getFluidMarker() {
        if (!hasDirectFluidMarker()) {
            return FluidStack.EMPTY;
        }
        final Fluid fluid = BuiltInRegistries.FLUID.get(fluidMarkerId);
        if (fluid == null || fluid == Fluids.EMPTY) {
            return FluidStack.EMPTY;
        }
        return new FluidStack(fluid, 1);
    }

    /**
     * 服务端权威入口：直接标记流体/气体（来自 {@code SetQuantityFluidMarkerPacket}）。
     * <p>{@code id == null} 等价于清除直接流体标记（回到物品标记语义）。</p>
     */
    public void setFluidMarker(final int form, @Nullable final ResourceLocation id, @Nullable final CompoundTag nbt) {
        if (id == null) {
            clearFluidMarker();
            return;
        }
        this.markerForm = form == FORM_GAS ? FORM_GAS : FORM_FLUID;
        this.fluidMarkerId = id;
        this.fluidMarkerNbt = nbt == null ? new CompoundTag() : nbt.copy();
        // 直接流体标记与物品标记互斥：清空标记槽，避免两种标记并存
        inventory.setItem(0, ItemStack.EMPTY);
        updateBlockedState();
        setChanged();
    }

    /** 清除直接流体标记，恢复为物品标记语义。 */
    public void clearFluidMarker() {
        this.markerForm = FORM_ITEM;
        this.fluidMarkerId = null;
        this.fluidMarkerNbt = new CompoundTag();
        updateBlockedState();
        setChanged();
    }

    /**
     * 设置物品标记（同时清除直接流体标记），用于「手持/Shift/JEI 拖入物品」的统一入口。
     */
    public void setItemMarker(final ItemStack stack) {
        if (stack.isEmpty()) {
            inventory.setItem(0, ItemStack.EMPTY);
        } else {
            inventory.setItem(0, stack.copyWithCount(1));
        }
        this.markerForm = FORM_ITEM;
        this.fluidMarkerId = null;
        this.fluidMarkerNbt = new CompoundTag();
        updateBlockedState();
        setChanged();
    }

    /** 该物品是否与当前物品标记完全相同（形态为物品、标记槽非空、同物品）。 */
    public boolean matchesItemMarker(final ItemStack stack) {
        if (stack.isEmpty() || markerForm != FORM_ITEM) {
            return false;
        }
        final ItemStack marker = getMarkerStack();
        return !marker.isEmpty() && ItemStack.isSameItemSameComponents(marker, stack);
    }

    /** 该流体 id 是否与当前流体/气体标记相同。 */
    public boolean matchesFluidMarker(@Nullable final ResourceLocation id) {
        return id != null && hasDirectFluidMarker() && id.equals(fluidMarkerId);
    }

    /**
     * 当前标记在 RS 网络里的<b>资源键</b>：物品 = 同一物品 + 同一数据组件补丁；
     * 流体 / 气体 = 同一流体 + 同一数据组件补丁。空标记 / 无法解析返回 {@code null}。
     *
     * <p><b>为什么要收敛到唯一入口</b>：入网、比对目标、销毁、同网络仲裁四处必须使用<b>同一个键</b>。
     * 曾经「入网带数据组件、销毁不带组件」的不对称会让销毁永远落空（带组件的流体 / 化工气体尤甚），
     * 表现为用户报告的「基础版怎么都不销毁」。</p>
     */
    @Nullable
    public ResourceKey networkResourceKey() {
        if (hasDirectFluidMarker()) {
            final Fluid fluid = BuiltInRegistries.FLUID.get(fluidMarkerId);
            if (fluid == null || fluid == Fluids.EMPTY) {
                return null;
            }
            final HolderLookup.Provider registries = level == null ? null : level.registryAccess();
            return new FluidResource(fluid, MarkerEntry.decodeComponents(fluidMarkerNbt, registries));
        }
        final ItemStack marker = getMarkerStack();
        if (marker.isEmpty() || markerForm != FORM_ITEM) {
            return null;
        }
        return ItemResource.ofItemStack(marker);
    }

    /** 本机声称控制的资源键（基础版只有一个标记位，故最多一个）：供同网络仲裁使用。 */
    public List<ResourceKey> claimedResources() {
        final ResourceKey key = networkResourceKey();
        return key == null ? List.of() : List.of(key);
    }

    /** 「过量销毁」的诊断记账器（由网络节点逐 tick 调用；条件不成立时请调 {@code idle()} 收尾）。 */
    public KeeperOverflow.Episode overflowLog() {
        return overflowLog;
    }

    // ==================== 同类存储（任务 3） ====================

    /** 物品同类存储（只接受标记资源；不匹配的插入会被原样退回，表现为「堵塞」）。 */
    public ItemStackHandler getItemStorage() {
        return storage;
    }

    /** 对外暴露的物品能力：本机同类存储 + 一个「网络同类资源」虚拟末槽（供物流从中取出）。 */
    public net.neoforged.neoforge.items.IItemHandler getItemHandler() {
        if (itemHandler == null) {
            itemHandler = new KeeperItemHandler();
        }
        return itemHandler;
    }

    /** 流体/气体同类存储。 */
    public MultiFluidCache getFluidStorage() {
        return fluidStorage;
    }

    /** 流体能力外观（供 NeoForge FluidHandler 能力使用）。 */
    public IFluidHandler getFluidHandler() {
        if (fluidHandler == null) {
            fluidHandler = new KeeperFluidHandler();
        }
        return fluidHandler;
    }

    /** 是否处于「堵塞」状态（内部存在与当前标记不匹配的资源）。 */
    public boolean isBlocked() {
        return blocked;
    }

    /**
     * 重算「堵塞」状态：内部只要有<b>任何一种</b>资源与当前标记不匹配即为堵塞。
     * <p>堵塞只是「停止输出」（不再把内容写入网络），绝不销毁任何内容：
     * 物品/流体仍留在本机，可被玩家或管道照常取出。</p>
     */
    public void updateBlockedState() {
        boolean mismatch = false;
        for (int i = 0; i < storage.getSlots() && !mismatch; i++) {
            final ItemStack stack = storage.getStackInSlot(i);
            if (!stack.isEmpty() && !matchesItemMarker(stack)) {
                mismatch = true;
            }
        }
        for (final MultiFluidCache.Entry entry : fluidStorage.entries()) {
            if (!matchesFluidMarker(entry.id())) {
                mismatch = true;
                break;
            }
        }
        blocked = mismatch;
        setChanged();
    }

    /**
     * 同类存储 → RS 网络回流（每 tick 由网络节点驱动）+ 「过量销毁」在<b>本机侧</b>的落点。
     *
     * <p><b>语义</b>（与高级版共用 {@link KeeperOverflow}）：「过量销毁」关闭时把本机同类存储
     * <b>原样</b>回流进网络（一个都不销毁）；开启时只把「目标 − 网络已有」的缺口部分送进网络，
     * 其余<b>有意销毁</b> —— 即「超出目标的那部分才销毁、绝不低于目标、绝不碰其它资源」。</p>
     *
     * <p><b>为什么销毁必须落在本机侧</b>：网络装不下（满仓）或本机被标记变更拦下时，资源会一直堆在
     * 本机 27 格缓冲里；若只在「网络存量」那一侧销毁，这些缓冲永远清不掉（用户报告的
     * 「基础版不管开不开都无法过量销毁」）。</p>
     *
     * @param yielded 同网络内由更权威的同伴负责的资源键（这些键只搬运、不销毁、不合成）
     */
    public void tickStorage(final Network network, final Set<ResourceKey> yielded) {
        if (network == null || blocked) {
            return;
        }
        final StorageNetworkComponent net = network.getComponent(StorageNetworkComponent.class);
        if (net == null) {
            return;
        }
        final ResourceKey key = networkResourceKey();
        // 只有「已标记（设定值 > 0）+ 开关打开 + 本资源由本机仲裁获胜」才允许销毁；
        // 未标记（下限 0）/ 开关关闭 / 让位 ⇒ 一律原样回流（绝不销毁）
        final boolean destroyAllowed =
            KeeperTarget.isMarked(targetAmount) && destroyOverflow && key != null && !yielded.contains(key);
        boolean changed = false;
        for (int i = 0; i < storage.getSlots(); i++) {
            final ItemStack inSlot = storage.getStackInSlot(i);
            if (inSlot.isEmpty() || !matchesItemMarker(inSlot)) {
                continue;
            }
            final ItemResource resource = ItemResource.ofItemStack(inSlot);
            final int count = inSlot.getCount();
            // 开启过量销毁：只把「目标 − 网络已有」的缺口送进网络（缺口为负 → 一个都不送）
            final long gap = destroyAllowed ? Math.max(0L, (long) targetAmount - net.get(resource)) : count;
            final long moved = gap <= 0L ? 0L
                : net.insert(resource, Math.min(count, gap), Action.EXECUTE, Actor.EMPTY);
            final long left = count - moved;
            // 销毁量由共用语义给出：以「网络 + 本机余量」为池，只销毁超出目标的部分，绝不低于目标
            final long destroy = destroyAllowed
                ? KeeperOverflow.localDestroyAmount(net.get(resource), left, targetAmount) : 0L;
            final long removed = moved + destroy;
            if (removed <= 0L) {
                continue;
            }
            storage.extractItem(i, (int) removed, false);
            if (destroy > 0L) {
                overflowLog.record(diagnosticLabel(), resource, targetAmount, destroy,
                    KeeperOverflow.excess(net.get(resource) + (left - destroy), targetAmount));
            }
            changed = true;
        }
        for (final MultiFluidCache.Entry entry : fluidStorage.entries()) {
            if (!matchesFluidMarker(entry.id())) {
                continue;
            }
            final Fluid fluid = BuiltInRegistries.FLUID.get(entry.id());
            if (fluid == null || fluid == Fluids.EMPTY) {
                continue;
            }
            final HolderLookup.Provider registries = level == null ? null : level.registryAccess();
            final FluidResource resource =
                new FluidResource(fluid, MarkerEntry.decodeComponents(entry.nbt(), registries));
            final long amount = entry.amount();
            final long gap = destroyAllowed ? Math.max(0L, (long) targetAmount - net.get(resource)) : amount;
            final long moved = gap <= 0L ? 0L
                : net.insert(resource, Math.min(amount, gap), Action.EXECUTE, Actor.EMPTY);
            final long left = amount - moved;
            final long destroy = destroyAllowed
                ? KeeperOverflow.localDestroyAmount(net.get(resource), left, targetAmount) : 0L;
            final long removed = moved + destroy;
            if (removed <= 0L) {
                continue;
            }
            fluidStorage.extract(entry.id(), entry.nbt(), removed);
            if (destroy > 0L) {
                overflowLog.record(diagnosticLabel(), resource, targetAmount, destroy,
                    KeeperOverflow.excess(net.get(resource) + (left - destroy), targetAmount));
            }
            changed = true;
        }
        if (changed) {
            setChanged();
        }
    }

    /** 日志诊断标签（机器名 + 坐标；销毁日志按「事件」聚合，不会刷屏）。 */
    public String diagnosticLabel() {
        return "quantity_keeper" + worldPosition.toShortString();
    }

    public int getTargetAmount() {
        return targetAmount;
    }

    /**
     * 设置目标数量（服务端权威）。
     * <p><b>下限放宽到 0</b>：0 / 负数 = 「未标记」——该项不参与维持 / 合成 / 销毁 / 缺料统计
     * （见 {@code KeeperTarget} 的「下限 0」口径）。玩家界面上的设定值原样保留，实际维持量由
     * 网络节点按「可达上限」钳制后再决定。</p>
     */
    public void setTargetAmount(final int targetAmount) {
        this.targetAmount = Math.max(0, targetAmount);
        setChanged();
    }

    public boolean isDestroyOverflow() {
        return destroyOverflow;
    }

    public void setDestroyOverflow(final boolean destroyOverflow) {
        this.destroyOverflow = destroyOverflow;
        setChanged();
    }

    public boolean isAutoCraftEnabled() {
        return autoCraftEnabled;
    }

    public void setAutoCraftEnabled(final boolean autoCraftEnabled) {
        this.autoCraftEnabled = autoCraftEnabled;
        setChanged();
    }

    public boolean hasAutocraftingUpgrade() {
        return countUpgrades(AUTOCRAFTING_UPGRADE) > 0;
    }

    /** 是否实际执行自动合成：自动合成开关打开 <b>且</b> 装有自动合成升级。 */
    public boolean shouldAutoCraft() {
        return autoCraftEnabled && hasAutocraftingUpgrade();
    }

    /**
     * 每秒销毁的过量数量：<b>无升级时很慢（基础 1 个/秒，逐个消失），升级越多越快，
     * 满级（速度/堆叠堆满）时批量销毁、几乎瞬间清完</b>。
     * 速度升级每个翻倍；堆叠升级是高级版（1 个 = 3 个速度升级，×8）。
     */
    public int getDestroyRate() {
        final int speed = Math.min(countUpgrades(SPEED_UPGRADE), 6);
        final int stack = Math.min(countUpgrades(STACK_UPGRADE), 6);
        // 指数上限钳到 2^24，避免 int 移位溢出后行为异常
        final int power = Math.min(speed + stack * 3, 24);
        return Config.quantityKeeperDestroyRate << power;
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

    @Override
    public net.minecraft.network.chat.Component getName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("TargetAmount", targetAmount);
        tag.putBoolean("DestroyOverflow", destroyOverflow);
        tag.putBoolean("AutoCraftEnabled", autoCraftEnabled);
        tag.put("Inventory", cretae.cookiewyq.rs_create_compat.support.RsccSlotNbt.write(inventory, registries));
        writeMarkerAndFluid(tag, registries);
        tag.put(TAG_STORAGE, storage.serializeNBT(registries));
    }

    @Override
    public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // 下限放宽到 0（0 = 未标记）；旧存档若为 0 也按「未标记」读取
        targetAmount = Math.max(0, tag.getInt("TargetAmount"));
        destroyOverflow = tag.getBoolean("DestroyOverflow");
        // 自动合成开关：<b>缺字段一律按「关」</b>（用户硬要求：放入自动合成升级只让按钮由禁用变可点，
        // 绝不自动打开；旧档没有这个字段时旧实现会回退成「开」，正是「偶发性自动打开」的来源）。
        // 玩家手动打开过的存档会带 AutoCraftEnabled=true，照常读回（不被默认值覆盖）。
        autoCraftEnabled = tag.getBoolean("AutoCraftEnabled");
        if (tag.contains("Inventory")) {
            loadingInventory = true;
            try {
                cretae.cookiewyq.rs_create_compat.support.RsccSlotNbt.read(
                    tag.getList("Inventory", net.minecraft.nbt.Tag.TAG_COMPOUND), inventory, registries);
            } finally {
                loadingInventory = false;
            }
        }
        readMarkerAndFluid(tag, registries);
        if (tag.contains(TAG_STORAGE)) {
            storage.deserializeNBT(registries, tag.getCompound(TAG_STORAGE));
        }
        updateBlockedState();
    }

    /** 写入标记形态 / 直接流体标记 / 流体同类存储（存档与掉落物 NBT 共用）。 */
    private void writeMarkerAndFluid(final CompoundTag tag, final HolderLookup.Provider registries) {
        tag.putInt(TAG_MARKER_FORM, markerForm);
        tag.putString(TAG_FLUID_MARKER_ID, fluidMarkerId == null ? "" : fluidMarkerId.toString());
        tag.put(TAG_FLUID_MARKER_NBT, fluidMarkerNbt == null ? new CompoundTag() : fluidMarkerNbt);
        fluidStorage.save(tag, TAG_FLUID_STORAGE);
    }

    /** 读取标记形态 / 直接流体标记 / 流体同类存储。 */
    private void readMarkerAndFluid(final CompoundTag tag, final HolderLookup.Provider registries) {
        markerForm = tag.contains(TAG_MARKER_FORM) ? tag.getInt(TAG_MARKER_FORM) : FORM_ITEM;
        final String rawId = tag.getString(TAG_FLUID_MARKER_ID);
        fluidMarkerId = rawId.isEmpty() ? null : ResourceLocation.tryParse(rawId);
        fluidMarkerNbt = tag.contains(TAG_FLUID_MARKER_NBT)
            ? tag.getCompound(TAG_FLUID_MARKER_NBT) : new CompoundTag();
        if (tag.contains(TAG_FLUID_STORAGE)) {
            fluidStorage.load(tag, TAG_FLUID_STORAGE);
        }
        if (fluidMarkerId == null && markerForm != FORM_ITEM) {
            markerForm = FORM_ITEM; // 非法组合（直接标记缺 id）回退为物品形态
        }
    }

    /**
     * 破坏方块时写入掉落物物品 NBT 的状态（零损耗）：
     * 标记形态 / 直接流体标记 / <b>流体同类存储</b>。
     * <p>物品同类存储与插件槽由破坏逻辑按实体掉落，这里不含它们，避免重复。</p>
     */
    public CompoundTag saveStateForItem() {
        stateDropped = true;
        final CompoundTag tag = new CompoundTag();
        writeMarkerAndFluid(tag, null);
        return tag;
    }

    /** 状态是否已随掉落物 NBT 带走（供方块在 onRemove 里判断是否需要补掉方块物品）。 */
    public boolean isStateDropped() {
        return stateDropped;
    }

    /** 放置时从掉落物 NBT 回填「标记形态 / 直接流体标记 / 流体同类存储」（零损耗）。 */
    public void loadPlacedState(final CompoundTag tag, final HolderLookup.Provider registries) {
        if (tag == null || tag.isEmpty()) {
            return;
        }
        readMarkerAndFluid(tag, registries);
        updateBlockedState();
        setChanged();
    }

    /** 供菜单同步的实时数据：0 = 目标数量，1 = 销毁开关，2 = 速度升级数，3 = 自动合成升级数，
     *  4 = 标记形态（0 物品 / 1 流体 / 2 气体），5 = 自动合成开关，6 = 是否有直接流体标记，
     *  7 = 是否堵塞。 */
    public net.minecraft.world.inventory.ContainerData getContainerData() {
        return new net.minecraft.world.inventory.ContainerData() {
            @Override
            public int get(final int index) {
                return switch (index) {
                    case 0 -> targetAmount;
                    case 1 -> destroyOverflow ? 1 : 0;
                    case 2 -> countUpgrades(SPEED_UPGRADE);
                    case 3 -> hasAutocraftingUpgrade() ? 1 : 0;
                    // 4：标记形态。物品形态恒为 FORM_ITEM（容器物品也不再反推流体）
                    case 4 -> hasDirectFluidMarker() ? markerForm : FORM_ITEM;
                    case 5 -> autoCraftEnabled ? 1 : 0;
                    case 6 -> hasDirectFluidMarker() ? 1 : 0;
                    case 7 -> blocked ? 1 : 0;
                    // 红石模式：走 RS 的 RedstoneModeSettings 映射（与 RS 原版机器同一套 0/1/2 编码）
                    case 8 -> com.refinedmods.refinedstorage.common.support.RedstoneModeSettings
                        .getRedstoneMode(getRedstoneMode());
                    default -> 0;
                };
            }

            @Override
            public void set(final int index, final int value) {
                // 由服务端按钮逻辑修改
            }

            @Override
            public int getCount() {
                return 9;
            }
        };
    }

    /**
     * 注册方块能力：
     * <ul>
     *     <li>RS 网络节点容器；</li>
     *     <li>{@link Capabilities.ItemHandler.BLOCK}：同类物品存储（只接受标记物品，
     *     不匹配的插入原样退回 = 物流方块自行回压，表现为「堵塞」且绝不吞掉物品）；</li>
     *     <li>{@link Capabilities.FluidHandler.BLOCK}：同类流体/气体存储（同样只接受标记资源，可取出）。</li>
     * </ul>
     */
    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.QUANTITY_KEEPER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
        event.registerBlockEntity(
            Capabilities.ItemHandler.BLOCK,
            RS_Create_Compat.QUANTITY_KEEPER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getItemHandler()
        );
        event.registerBlockEntity(
            Capabilities.FluidHandler.BLOCK,
            RS_Create_Compat.QUANTITY_KEEPER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getFluidHandler()
        );
    }

    /** 网络内与当前标记同类的物品存量（无网络 / 非物品标记返回 0）。 */
    private long networkMarkerItemAmount() {
        final ItemStack marker = getMarkerStack();
        if (marker.isEmpty() || markerForm != FORM_ITEM || level == null || level.isClientSide()) {
            return 0L;
        }
        final Network network = mainNetworkNode.getNetworkOrNull();
        if (network == null) {
            return 0L;
        }
        final StorageNetworkComponent net = network.getComponent(StorageNetworkComponent.class);
        return net == null ? 0L : net.get(ItemResource.ofItemStack(marker));
    }

    /** 从网络提取当前标记物品（simulate 只做模拟）。 */
    private long extractMarkerItemFromNetwork(final long amount, final boolean simulate) {
        final ItemStack marker = getMarkerStack();
        if (amount <= 0 || marker.isEmpty() || markerForm != FORM_ITEM) {
            return 0L;
        }
        final Network network = mainNetworkNode.getNetworkOrNull();
        if (network == null) {
            return 0L;
        }
        final StorageNetworkComponent net = network.getComponent(StorageNetworkComponent.class);
        if (net == null) {
            return 0L;
        }
        return net.extract(ItemResource.ofItemStack(marker), amount,
            simulate ? Action.SIMULATE : Action.EXECUTE, Actor.EMPTY);
    }

    /** 从网络提取当前标记流体/气体（simulate 只做模拟）。 */
    private long extractMarkerFluidFromNetwork(final long amount, final boolean simulate) {
        if (amount <= 0 || !hasDirectFluidMarker()) {
            return 0L;
        }
        final Fluid fluid = BuiltInRegistries.FLUID.get(fluidMarkerId);
        if (fluid == null || fluid == Fluids.EMPTY) {
            return 0L;
        }
        final Network network = mainNetworkNode.getNetworkOrNull();
        if (network == null) {
            return 0L;
        }
        final StorageNetworkComponent net = network.getComponent(StorageNetworkComponent.class);
        if (net == null) {
            return 0L;
        }
        final HolderLookup.Provider registries = level == null ? null : level.registryAccess();
        final FluidResource resource = new FluidResource(fluid,
            MarkerEntry.decodeComponents(fluidMarkerNbt, registries));
        return net.extract(resource, amount, simulate ? Action.SIMULATE : Action.EXECUTE, Actor.EMPTY);
    }

    /**
     * 物品同类存储对外能力：前 {@link #STORAGE_SLOTS} 个槽是本机同类存储，末尾一个虚拟槽代表
     * RS 网络内与标记同类的物品（供漏斗/管道从中取出）。
     * <p>插入：不匹配标记的物品<b>原样退回</b>（返回 0 接收 = 上游自行回压，绝不吞掉）；
     * 取出：本机优先，虚拟槽从网络取。</p>
     */
    private final class KeeperItemHandler implements net.neoforged.neoforge.items.IItemHandler {
        @Override
        public int getSlots() {
            return STORAGE_SLOTS + 1;
        }

        @Override
        public ItemStack getStackInSlot(final int slot) {
            if (slot >= 0 && slot < STORAGE_SLOTS) {
                return storage.getStackInSlot(slot);
            }
            final long amount = networkMarkerItemAmount();
            if (amount <= 0) {
                return ItemStack.EMPTY;
            }
            return getMarkerStack().copyWithCount((int) Math.min(STORAGE_SLOT_LIMIT, amount));
        }

        @Override
        public ItemStack insertItem(final int slot, final ItemStack stack, final boolean simulate) {
            if (stack.isEmpty() || !matchesItemMarker(stack)) {
                return stack; // 只接受同一种资源：不匹配则原样退回（堵塞，不销毁）
            }
            ItemStack remainder = stack;
            for (int i = 0; i < STORAGE_SLOTS && !remainder.isEmpty(); i++) {
                remainder = storage.insertItem(i, remainder, simulate);
            }
            if (!simulate && remainder.getCount() != stack.getCount()) {
                updateBlockedState();
            }
            return remainder;
        }

        @Override
        public ItemStack extractItem(final int slot, final int amount, final boolean simulate) {
            if (amount <= 0) {
                return ItemStack.EMPTY;
            }
            if (slot >= 0 && slot < STORAGE_SLOTS) {
                if (!storage.getStackInSlot(slot).isEmpty()) {
                    return storage.extractItem(slot, amount, simulate);
                }
                // 本机槽为空时回退到网络同类资源，保证「从里面输出」始终可用
                final long taken = extractMarkerItemFromNetwork(amount, simulate);
                return taken <= 0 ? ItemStack.EMPTY : getMarkerStack().copyWithCount((int) taken);
            }
            final long taken = extractMarkerItemFromNetwork(amount, simulate);
            return taken <= 0 ? ItemStack.EMPTY : getMarkerStack().copyWithCount((int) taken);
        }

        @Override
        public int getSlotLimit(final int slot) {
            return STORAGE_SLOT_LIMIT;
        }

        @Override
        public boolean isItemValid(final int slot, final ItemStack stack) {
            return matchesItemMarker(stack);
        }
    }

    /**
     * 流体/气体同类存储的 {@link IFluidHandler} 外观：
     * <ul>
     *     <li>{@code fill} 只接受与当前标记相同的流体（不匹配返回 {@link FluidStack#EMPTY}，
     *     上游（管道/物流）据此自行回压，表现为「堵塞」且不销毁流体）；</li>
     *     <li>{@code drain} 任意已存条目都可取出（含标记变更后遗留的不匹配流体），绝不吞掉。</li>
     * </ul>
     */
    private final class KeeperFluidHandler implements IFluidHandler {
        @Override
        public int getTanks() {
            return fluidStorage.getKinds() + 1; // 末尾一个空槽供外部填充
        }

        @Override
        public FluidStack getFluidInTank(final int tank) {
            final List<MultiFluidCache.Entry> entries = fluidStorage.entries();
            if (tank < 0 || tank >= entries.size()) {
                return FluidStack.EMPTY;
            }
            final MultiFluidCache.Entry entry = entries.get(tank);
            final Fluid fluid = BuiltInRegistries.FLUID.get(entry.id());
            if (fluid == null || fluid == Fluids.EMPTY) {
                return FluidStack.EMPTY;
            }
            return new FluidStack(fluid, (int) Math.min(Integer.MAX_VALUE, entry.amount()));
        }

        @Override
        public int getTankCapacity(final int tank) {
            return (int) Math.min(Integer.MAX_VALUE, FLUID_STORAGE_CAPACITY);
        }

        @Override
        public boolean isFluidValid(final int tank, final FluidStack stack) {
            return stack != null && !stack.isEmpty()
                && matchesFluidMarker(BuiltInRegistries.FLUID.getKey(stack.getFluid()));
        }

        @Override
        public int fill(final FluidStack resource, final FluidAction action) {
            if (resource == null || resource.isEmpty() || !isFluidValid(0, resource)) {
                return 0;
            }
            final ResourceLocation id = BuiltInRegistries.FLUID.getKey(resource.getFluid());
            final CompoundTag nbt = MarkerEntry.encodeComponents(
                resource.getComponentsPatch(), level == null ? null : level.registryAccess());
            final long accepted = action.simulate()
                ? Math.min(fluidStorage.getFreeSpace(), resource.getAmount())
                : fluidStorage.insert(id, nbt, resource.getAmount());
            if (!action.simulate() && accepted > 0) {
                updateBlockedState();
            }
            return (int) Math.min(Integer.MAX_VALUE, accepted);
        }

        @Override
        public FluidStack drain(final FluidStack resource, final FluidAction action) {
            if (resource == null || resource.isEmpty()) {
                return FluidStack.EMPTY;
            }
            final ResourceLocation id = BuiltInRegistries.FLUID.getKey(resource.getFluid());
            final CompoundTag nbt = MarkerEntry.encodeComponents(resource.getComponentsPatch());
            final long local = Math.min(resource.getAmount(), fluidStorage.getAmount(id, nbt));
            if (local <= 0) {
                // 本机为空时回退到网络同类资源，保证「从里面输出」始终可用
                final long fromNetwork = extractMarkerFluidFromNetwork(resource.getAmount(), action.simulate());
                return fromNetwork <= 0 ? FluidStack.EMPTY
                    : new FluidStack(resource.getFluid(), (int) Math.min(Integer.MAX_VALUE, fromNetwork));
            }
            return drainById(id, nbt, local, action);
        }

        @Override
        public FluidStack drain(final int maxDrain, final FluidAction action) {
            final List<MultiFluidCache.Entry> entries = fluidStorage.entries();
            if (entries.isEmpty() || maxDrain <= 0) {
                final Fluid fluid = hasDirectFluidMarker() ? BuiltInRegistries.FLUID.get(fluidMarkerId) : null;
                if (fluid == null || fluid == Fluids.EMPTY) {
                    return FluidStack.EMPTY;
                }
                final long fromNetwork = extractMarkerFluidFromNetwork(maxDrain, action.simulate());
                return fromNetwork <= 0 ? FluidStack.EMPTY
                    : new FluidStack(fluid, (int) Math.min(Integer.MAX_VALUE, fromNetwork));
            }
            final MultiFluidCache.Entry entry = entries.get(0);
            return drainById(entry.id(), entry.nbt(), Math.min(maxDrain, entry.amount()), action);
        }

        private FluidStack drainById(final ResourceLocation id, final CompoundTag nbt, final long amount,
                                     final FluidAction action) {
            if (amount <= 0) {
                return FluidStack.EMPTY;
            }
            final Fluid fluid = BuiltInRegistries.FLUID.get(id);
            if (fluid == null || fluid == Fluids.EMPTY) {
                return FluidStack.EMPTY;
            }
            final long extracted = action.simulate()
                ? amount
                : fluidStorage.extract(id, nbt, amount);
            if (!action.simulate() && extracted > 0) {
                setChanged();
            }
            return new FluidStack(fluid, (int) Math.min(Integer.MAX_VALUE, extracted));
        }
    }
}
