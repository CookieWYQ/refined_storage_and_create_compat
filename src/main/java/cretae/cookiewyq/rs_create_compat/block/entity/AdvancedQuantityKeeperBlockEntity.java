package cretae.cookiewyq.rs_create_compat.block.entity;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.network.AdvancedQuantityKeeperNetworkNode;
import cretae.cookiewyq.rs_create_compat.support.KeeperOverflow;
import cretae.cookiewyq.rs_create_compat.support.KeeperSlotConfig;
import cretae.cookiewyq.rs_create_compat.support.KeeperTarget;
import cretae.cookiewyq.rs_create_compat.support.MarkerEntry;
import cretae.cookiewyq.rs_create_compat.support.MultiFluidCache;
import cretae.cookiewyq.rs_create_compat.support.RsccSlotNbt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * 高级资源定量保持器：把 4 个资源定量保持器融合进一个方块，拥有 <b>4 个各自独立的配置槽位</b>。
 * <ul>
 *     <li>每个槽位可独立标记资源（物品 / 流体 / 气体）、设置目标数量、以及是否自动合成；</li>
 *     <li><b>允许空槽</b>（{@link KeeperSlotConfig#FORM_EMPTY}），标了哪几个就只应用哪几个，<b>顺序无关</b>；</li>
 *     <li>每槽一份独立存储：物品 27 格 / 流体 128000 mB（4 份）；</li>
 *     <li><b>只接受 4 个槽位已标记的类型</b>，其它资源一律原样退回，绝不销毁；</li>
 *     <li>标记变更导致槽内出现不再匹配的资源时，该槽置「堵塞」并停止输出，但资源仍可取走。</li>
 * </ul>
 */
public class AdvancedQuantityKeeperBlockEntity
    extends AbstractBaseNetworkNodeContainerBlockEntity<AdvancedQuantityKeeperNetworkNode> {

    /** 诊断日志：插件槽异常只读校验的结果（不刷屏，只在异常组合变化时打一条）。 */
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** 配置槽位数量。 */
    public static final int SLOT_COUNT = 4;
    /** 每槽物品存储格数。 */
    public static final int STORAGE_SLOTS = 27;
    /** 每槽物品存储单格上限。 */
    public static final int STORAGE_SLOT_LIMIT = 64;
    /** 每槽流体/气体存储容量（mB，128 桶）。 */
    public static final long FLUID_STORAGE_CAPACITY = 128_000L;

    /** 标记形态常量（与 {@link KeeperSlotConfig} 保持一致）。 */
    public static final int FORM_ITEM = KeeperSlotConfig.FORM_ITEM;
    public static final int FORM_FLUID = KeeperSlotConfig.FORM_FLUID;
    public static final int FORM_GAS = KeeperSlotConfig.FORM_GAS;
    public static final int FORM_EMPTY = KeeperSlotConfig.FORM_EMPTY;

    // ===== ContainerData 索引表（每槽 6 个数值槽 + 3 个全局槽）=====
    /** 每槽占用的数值槽数：0=目标数量，1=标记形态，2=自动合成开关（全局升级决定），3=堵塞，
     *  4=是否有标记，5=是否过量销毁。
     *  <p><b>「已存数量」那一格已删除</b>（用户明确要求界面上不再显示「已存 / 目标」那一段）——
     *  顺带也避开了「ContainerData 只按 short 同步、大数值会被截断」的老问题。</p> */
    public static final int DATA_PER_SLOT = 6;
    public static final int DATA_SLOT_TARGET = 0;
    public static final int DATA_SLOT_FORM = 1;
    public static final int DATA_SLOT_AUTOCRAFT = 2;
    public static final int DATA_SLOT_BLOCKED = 3;
    public static final int DATA_SLOT_HAS_MARKER = 4;
    public static final int DATA_SLOT_DESTROY_OVERFLOW = 5;
    /** 全局：速度升级数量（4*6=24）。 */
    public static final int DATA_SPEED_UPGRADES = SLOT_COUNT * DATA_PER_SLOT;
    /** 全局：是否装有自动合成升级。 */
    public static final int DATA_HAS_AUTOCRAFT_UPGRADE = DATA_SPEED_UPGRADES + 1;
    /** 全局：红石模式（0=忽略 / 1=高电平工作 / 2=低电平工作，与 RS 原版机器同一套编码）。 */
    public static final int DATA_REDSTONE_MODE = DATA_HAS_AUTOCRAFT_UPGRADE + 1;
    /** 数值槽总数。 */
    public static final int DATA_COUNT = DATA_REDSTONE_MODE + 1;

    private static final ResourceLocation SPEED_UPGRADE =
        ResourceLocation.fromNamespaceAndPath("refinedstorage", "speed_upgrade");
    private static final ResourceLocation STACK_UPGRADE =
        ResourceLocation.fromNamespaceAndPath("refinedstorage", "stack_upgrade");
    private static final ResourceLocation AUTOCRAFTING_UPGRADE =
        ResourceLocation.fromNamespaceAndPath("refinedstorage", "autocrafting_upgrade");

    private static final String TAG_INVENTORY = "Inventory";
    private static final String TAG_CONFIGS = "Configs";
    private static final String TAG_CONFIG_FORM = "Form";
    private static final String TAG_CONFIG_FLUID_ID = "FluidId";
    private static final String TAG_CONFIG_FLUID_NBT = "FluidNbt";
    private static final String TAG_CONFIG_TARGET = "Target";
    private static final String TAG_CONFIG_AUTOCRAFT = "Autocraft";
    private static final String TAG_CONFIG_DESTROY_OVERFLOW = "DestroyOverflow";
    private static final String TAG_ITEM_STORAGE = "ItemStorage";
    private static final String TAG_FLUID_STORAGE = "FluidStorage";

    /** 界面容器：0..3 = ghost 标记槽，4..9 = 插件槽。 */
    private final SimpleContainer inventory = new SimpleContainer(SLOT_COUNT + 6);

    /** 每槽独立的物品同类存储（只接受该槽标记的物品）。 */
    private final ItemStackHandler[] itemStorages = new ItemStackHandler[SLOT_COUNT];
    /** 每槽独立的流体/气体同类存储。 */
    private final MultiFluidCache[] fluidStorages = new MultiFluidCache[SLOT_COUNT];

    /** 每槽直接标记的流体/气体注册名（null = 该槽无流体标记）。 */
    private final ResourceLocation[] fluidMarkerIds = new ResourceLocation[SLOT_COUNT];
    /** 每槽直接标记的流体/气体数据组件 NBT。 */
    private final CompoundTag[] fluidMarkerNbts = new CompoundTag[SLOT_COUNT];
    /** 每槽标记形态（物品/流体/气体）。 */
    private final int[] fluidForms = new int[SLOT_COUNT];

    /** 每槽目标数量。 */
    private final int[] targets = new int[SLOT_COUNT];
    /**
     * 每槽自动合成开关（<b>默认关闭</b>）。
     *
     * <p><b>「能力可用」与「开关状态」分离</b>：是否能合成只取决于全局的「自动合成升级」
     * （{@link #hasAutocraftingUpgrade()}，决定按钮是否可点）；本数组才是玩家的逐槽开关。
     * 新机器 / 新插入升级时开关一律为关 —— <b>放入升级只把按钮由「禁用」变为「可点」，不会自动打开</b>；
     * 已有存档里玩家<b>手动打开过</b>的开关由 NBT（{@code Configs[i].Autocraft}）原样读回
     * （缺该字段的旧档按「关」处理，见 {@link #readConfigs}）。</p>
     */
    private final boolean[] autoCraft = new boolean[SLOT_COUNT];
    /** 每槽是否开启「过量销毁」：超过目标数量的同种资源会被销毁（默认关闭，绝不动玩家资源）。 */
    private final boolean[] destroyOverflow = new boolean[SLOT_COUNT];
    /** 每槽是否处于「堵塞」状态。 */
    private final boolean[] blocked = new boolean[SLOT_COUNT];
    /** 每槽「过量销毁」的诊断记账（服务端权威；只记账 + 打日志，不做搬运）。 */
    private final KeeperOverflow.Episode[] overflowLogs = new KeeperOverflow.Episode[SLOT_COUNT];

    private IFluidHandler fluidHandler;
    private net.neoforged.neoforge.items.IItemHandler itemHandler;
    /** 状态是否已随掉落物 NBT 带走（避免创造模式拆除时漏掉流体存储）。 */
    private boolean stateDropped;
    /** 从 NBT 载入库存期间跳过即时校验，避免在 level 尚未就绪时丢物品。 */
    private boolean loadingInventory;
    /** 「插件槽异常」诊断的节流签名：只在异常组合变化时打一条日志，绝不刷屏（只读，不改物品）。 */
    private String lastUpgradeAnomaly = "";

    public AdvancedQuantityKeeperBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.ADVANCED_QUANTITY_KEEPER_BLOCK_ENTITY.get(), pos, state,
            new AdvancedQuantityKeeperNetworkNode());
        this.mainNetworkNode.setBlockEntity(this);
        for (int i = 0; i < SLOT_COUNT; i++) {
            final int slot = i;
            targets[i] = Config.quantityKeeperDefaultTarget;
            autoCraft[i] = false; // 默认关闭：装升级只让按钮可用，须玩家手动开
            fluidMarkerNbts[i] = new CompoundTag();
            fluidForms[i] = FORM_ITEM;
            overflowLogs[i] = new KeeperOverflow.Episode();
            itemStorages[i] = new ItemStackHandler(STORAGE_SLOTS) {
                @Override
                public boolean isItemValid(final int index, final ItemStack stack) {
                    return matchesItemMarker(slot, stack);
                }

                @Override
                protected int getStackLimit(final int index, final ItemStack stack) {
                    return Math.min(STORAGE_SLOT_LIMIT, stack.getMaxStackSize());
                }

                @Override
                protected void onContentsChanged(final int index) {
                    setChanged();
                    updateBlockedState();
                }
            };
            fluidStorages[i] = new MultiFluidCache(FLUID_STORAGE_CAPACITY);
        }
        // 容量变化时刷新节点状态 + 即时校验插件槽（约束不落空）
        inventory.addListener(container -> {
            if (level != null && !level.isClientSide() && !loadingInventory) {
                enforceUpgradeCaps();
            }
            setChanged();
        });
    }

    public AdvancedQuantityKeeperNetworkNode getNode() {
        return mainNetworkNode;
    }

    public SimpleContainer getInventory() {
        return inventory;
    }

    // ==================== 槽位配置视图 ====================

    /** 构建第 {@code slot} 槽的只读配置视图（空槽返回 form=3）。 */
    public KeeperSlotConfig getSlotConfig(final int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            return KeeperSlotConfig.EMPTY;
        }
        final ResourceLocation fluidId = fluidMarkerIds[slot];
        if (fluidId != null) {
            return new KeeperSlotConfig(fluidForms[slot], fluidId, fluidMarkerNbts[slot],
                targets[slot], autoCraft[slot]);
        }
        final ItemStack marker = inventory.getItem(slot);
        if (!marker.isEmpty()) {
            final ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(marker.getItem());
            final HolderLookup.Provider registries = level == null ? null : level.registryAccess();
            return new KeeperSlotConfig(FORM_ITEM, itemId,
                MarkerEntry.encodeComponents(marker.getComponentsPatch(), registries),
                targets[slot], autoCraft[slot]);
        }
        return new KeeperSlotConfig(FORM_EMPTY, null, new CompoundTag(), targets[slot], autoCraft[slot]);
    }

    /** 第 {@code slot} 槽是否已标记资源（非空槽）。 */
    public boolean hasMarker(final int slot) {
        return !getSlotConfig(slot).isEmpty();
    }

    /** 是否存在「直接标记」的流体/气体。 */
    public boolean hasDirectFluidMarker(final int slot) {
        return slot >= 0 && slot < SLOT_COUNT
            && fluidForms[slot] != FORM_ITEM && fluidMarkerIds[slot] != null;
    }

    /** 第 {@code slot} 槽直接标记的流体/气体注册名（无则 null）。 */
    @Nullable
    public ResourceLocation getFluidMarkerId(final int slot) {
        return slot >= 0 && slot < SLOT_COUNT ? fluidMarkerIds[slot] : null;
    }

    /** 第 {@code slot} 槽直接标记的流体/气体数据组件 NBT（无则空 tag）。 */
    public CompoundTag getFluidMarkerNbt(final int slot) {
        return slot >= 0 && slot < SLOT_COUNT && fluidMarkerNbts[slot] != null
            ? fluidMarkerNbts[slot] : new CompoundTag();
    }

    /** 该物品是否与第 {@code slot} 槽的物品标记完全相同（形态为物品、标记非空、同物品同组件）。 */
    public boolean matchesItemMarker(final int slot, final ItemStack stack) {
        if (stack.isEmpty() || slot < 0 || slot >= SLOT_COUNT || fluidMarkerIds[slot] != null) {
            return false;
        }
        final ItemStack marker = inventory.getItem(slot);
        return !marker.isEmpty() && ItemStack.isSameItemSameComponents(marker, stack);
    }

    /** 该流体 id 是否与第 {@code slot} 槽的流体/气体标记相同。 */
    public boolean matchesFluidMarker(final int slot, @Nullable final ResourceLocation id) {
        return id != null && slot >= 0 && slot < SLOT_COUNT
            && hasDirectFluidMarker(slot) && id.equals(fluidMarkerIds[slot]);
    }

    /**
     * 设置第 {@code slot} 槽的物品标记（同时清除该槽流体标记）；空栈 = 清除该槽标记。
     * 标记变更后重算堵塞状态（残留资源不匹配则停止输出，但绝不销毁）。
     */
    public void setItemMarker(final int slot, final ItemStack stack) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            return;
        }
        inventory.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
        fluidMarkerIds[slot] = null;
        fluidMarkerNbts[slot] = new CompoundTag();
        fluidForms[slot] = FORM_ITEM;
        updateBlockedState();
        setChanged();
    }

    /**
     * 设置第 {@code slot} 槽的流体/气体标记（与物品标记互斥）；{@code id == null} 等价于清除该槽标记。
     */
    public void setFluidMarker(final int slot, final int form,
                               @Nullable final ResourceLocation id, @Nullable final CompoundTag nbt) {
        if (slot < 0 || slot >= SLOT_COUNT || id == null) {
            clearSlot(slot);
            return;
        }
        inventory.setItem(slot, ItemStack.EMPTY);
        fluidForms[slot] = form == FORM_GAS ? FORM_GAS : FORM_FLUID;
        fluidMarkerIds[slot] = id;
        fluidMarkerNbts[slot] = nbt == null ? new CompoundTag() : nbt.copy();
        updateBlockedState();
        setChanged();
    }

    /** 清除第 {@code slot} 槽的标记（该槽原有资源保留，可取出，绝不销毁）。 */
    public void clearSlot(final int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            return;
        }
        inventory.setItem(slot, ItemStack.EMPTY);
        fluidMarkerIds[slot] = null;
        fluidMarkerNbts[slot] = new CompoundTag();
        fluidForms[slot] = FORM_ITEM;
        updateBlockedState();
        setChanged();
    }

    public int getTarget(final int slot) {
        return targets[slot];
    }

    /**
     * 设置某槽目标数量（服务端权威）。
     * <p><b>下限放宽到 0</b>：0 / 负数 = 「未标记」——该槽不参与维持 / 合成 / 销毁 / 缺料统计
     * （见 {@code KeeperTarget} 的「下限 0」口径）。界面上的设定值原样保留，实际维持量由网络节点
     * 按「可达上限」钳制后再决定。</p>
     */
    public void setTarget(final int slot, final int value) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            return;
        }
        targets[slot] = Math.max(0, value);
        setChanged();
    }

    public boolean isAutoCraft(final int slot) {
        return autoCraft[slot];
    }

    /**
     * 设置某槽的自动合成开关（服务端权威）。
     * <p><b>二次校验</b>：没有装入自动合成升级时，<b>开启</b>请求一律忽略（客户端按钮本来就被禁用，
     * 这里再挡一层，防止改包 / 指令越权）；<b>关闭</b>请求永远允许（避免升级被拔掉后开关卡死在「开」）。
     */
    public void setAutoCraft(final int slot, final boolean enabled) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            return;
        }
        if (enabled && !hasAutocraftingUpgrade()) {
            return;
        }
        autoCraft[slot] = enabled;
        setChanged();
    }

    /** 该槽是否开启「过量销毁」（每槽独立开关，默认关闭）。 */
    public boolean isDestroyOverflow(final int slot) {
        return slot >= 0 && slot < SLOT_COUNT && destroyOverflow[slot];
    }

    /** 设置某槽的「过量销毁」开关（每槽独立；与全局的自动合成升级无关）。 */
    public void setDestroyOverflow(final int slot, final boolean enabled) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            return;
        }
        destroyOverflow[slot] = enabled;
        setChanged();
    }

    /**
     * 第 {@code slot} 槽标记在 RS 网络里的<b>资源键</b>（物品 = 同一物品 + 同一数据组件补丁；
     * 流体 / 气体 = 同一流体 + 同一数据组件补丁）。空槽 / 无法解析返回 {@code null}。
     *
     * <p><b>为什么要收敛到唯一入口</b>：入网、比对目标、销毁、同网络仲裁四处必须使用<b>同一个键</b>；
     * 「入网带组件、销毁不带组件」的不对称会让销毁永远落空。</p>
     */
    @Nullable
    public ResourceKey networkResourceKey(final int slot) {
        // 这里刻意不调 hasMarker()：后者会构造 KeeperSlotConfig 并编码数据组件补丁，
        // 而本方法每 tick 会被同网络仲裁逐同伴调用，需要保持轻量。
        if (slot < 0 || slot >= SLOT_COUNT) {
            return null;
        }
        if (hasDirectFluidMarker(slot)) {
            final ResourceLocation id = fluidMarkerIds[slot];
            final Fluid fluid = id == null ? null : BuiltInRegistries.FLUID.get(id);
            if (fluid == null || fluid == Fluids.EMPTY) {
                return null;
            }
            final HolderLookup.Provider registries = level == null ? null : level.registryAccess();
            return new FluidResource(fluid, MarkerEntry.decodeComponents(fluidMarkerNbts[slot], registries));
        }
        final ItemStack marker = inventory.getItem(slot);
        if (marker.isEmpty()) {
            return null;
        }
        return ItemResource.ofItemStack(marker);
    }

    /** 本机声称控制的全部资源键（每个已标记且可解析的槽一个）：供同网络仲裁使用。 */
    public List<ResourceKey> claimedResources() {
        final List<ResourceKey> keys = new java.util.ArrayList<>(SLOT_COUNT);
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            final ResourceKey key = networkResourceKey(slot);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys.isEmpty() ? List.of() : List.copyOf(keys);
    }

    /** 第 {@code slot} 槽「过量销毁」的诊断记账器（由网络节点逐 tick 调用）。 */
    public KeeperOverflow.Episode overflowLog(final int slot) {
        return overflowLogs[slot];
    }

    /** 日志诊断标签（机器名 + 坐标；整机级的日志用这个，不带槽位后缀）。 */
    public String diagnosticLabel() {
        return "advanced_quantity_keeper" + worldPosition.toShortString();
    }

    /** 日志诊断标签（机器名 + 坐标 + 槽位；销毁日志按「事件」聚合，不会刷屏）。 */
    public String diagnosticLabel(final int slot) {
        return diagnosticLabel() + " slot" + slot;
    }

    // ==================== 存储访问 ====================

    public ItemStackHandler getItemStorage(final int slot) {
        return itemStorages[slot];
    }

    public MultiFluidCache getFluidStorage(final int slot) {
        return fluidStorages[slot];
    }

    /** 对外暴露的物品能力（聚合 4 槽 + 每槽一个「网络同类资源」虚拟末槽）。 */
    public net.neoforged.neoforge.items.IItemHandler getItemHandler() {
        if (itemHandler == null) {
            itemHandler = new KeeperItemHandler();
        }
        return itemHandler;
    }

    /** 对外暴露的流体能力（每槽一个 tank）。 */
    public IFluidHandler getFluidHandler() {
        if (fluidHandler == null) {
            fluidHandler = new KeeperFluidHandler();
        }
        return fluidHandler;
    }

    /** 任一槽堵塞即为堵塞。 */
    public boolean isBlocked() {
        for (final boolean b : blocked) {
            if (b) {
                return true;
            }
        }
        return false;
    }

    public boolean isBlocked(final int slot) {
        return slot >= 0 && slot < SLOT_COUNT && blocked[slot];
    }

    /**
     * 重算每槽「堵塞」状态：某槽内只要存在与该槽当前标记不匹配的资源即为堵塞。
     * <p>堵塞只是「停止输出」：内容原样保留，玩家/管道照常可取走。</p>
     */
    public void updateBlockedState() {
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            blocked[slot] = computeBlocked(slot);
        }
        setChanged();
    }

    private boolean computeBlocked(final int slot) {
        final ItemStackHandler storage = itemStorages[slot];
        for (int i = 0; i < storage.getSlots(); i++) {
            final ItemStack stack = storage.getStackInSlot(i);
            if (!stack.isEmpty() && !matchesItemMarker(slot, stack)) {
                return true;
            }
        }
        for (final MultiFluidCache.Entry entry : fluidStorages[slot].entries()) {
            if (!matchesFluidMarker(slot, entry.id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 同类存储 → RS 网络回流（由网络节点每 tick 驱动）+ 「过量销毁」在<b>本机侧</b>的落点：
     * 只搬运与各槽标记匹配的资源；某槽堵塞时跳过该槽（内容原样保留）。
     *
     * <p><b>语义</b>（与基础版共用 {@link KeeperOverflow}）：「过量销毁」关闭 ⇒ 原样回流、一个都不销毁；
     * 开启且该资源键由本机仲裁获胜 ⇒ 只把「目标 − 网络已有」的缺口送进网络，其余<b>有意销毁</b>
     * （超出目标的那部分才销毁，绝不低于目标，绝不碰其它资源）。</p>
     *
     * @param yielded 同网络内由更权威的同伴负责的资源键（这些键只搬运、不销毁、不合成）
     */
    public void tickStorage(final Network network, final Set<ResourceKey> yielded) {
        if (network == null) {
            return;
        }
        final StorageNetworkComponent net = network.getComponent(StorageNetworkComponent.class);
        if (net == null) {
            return;
        }
        boolean changed = false;
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (blocked[slot]) {
                continue;
            }
            final ResourceKey slotKey = networkResourceKey(slot);
            // 只有「本槽已标记（设定值 > 0）+ 本槽开关打开 + 本资源由本机仲裁获胜」才允许销毁；
            // 未标记（下限 0）/ 开关关闭 / 让位 ⇒ 一律原样回流（绝不销毁）
            final boolean destroyAllowed = KeeperTarget.isMarked(targets[slot])
                && destroyOverflow[slot] && slotKey != null && !yielded.contains(slotKey);
            final ItemStackHandler storage = itemStorages[slot];
            for (int i = 0; i < storage.getSlots(); i++) {
                final ItemStack inSlot = storage.getStackInSlot(i);
                if (inSlot.isEmpty() || !matchesItemMarker(slot, inSlot)) {
                    continue;
                }
                final ItemResource resource = ItemResource.ofItemStack(inSlot);
                final int count = inSlot.getCount();
                // 开启过量销毁时：只把「目标 − 网络已有」的缺口部分送进网络，其余销毁
                final long gap = destroyAllowed ? Math.max(0L, (long) targets[slot] - net.get(resource)) : count;
                final long moved = gap <= 0L ? 0L
                    : net.insert(resource, Math.min(count, gap), Action.EXECUTE, Actor.EMPTY);
                final long left = count - moved;
                // 销毁量由共用语义给出：以「网络 + 本机余量」为池，只销毁超出目标的部分，绝不低于目标
                final long destroy = destroyAllowed
                    ? KeeperOverflow.localDestroyAmount(net.get(resource), left, targets[slot]) : 0L;
                final long removed = moved + destroy;
                if (removed <= 0L) {
                    continue;
                }
                storage.extractItem(i, (int) removed, false);
                if (destroy > 0L) {
                    overflowLogs[slot].record(diagnosticLabel(slot), resource, targets[slot], destroy,
                        KeeperOverflow.excess(net.get(resource) + (left - destroy), targets[slot]));
                }
                changed = true;
            }
            for (final MultiFluidCache.Entry entry : fluidStorages[slot].entries()) {
                if (!matchesFluidMarker(slot, entry.id())) {
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
                final long gap = destroyAllowed ? Math.max(0L, (long) targets[slot] - net.get(resource)) : amount;
                final long moved = gap <= 0L ? 0L
                    : net.insert(resource, Math.min(amount, gap), Action.EXECUTE, Actor.EMPTY);
                final long left = amount - moved;
                final long destroy = destroyAllowed
                    ? KeeperOverflow.localDestroyAmount(net.get(resource), left, targets[slot]) : 0L;
                final long removed = moved + destroy;
                if (removed <= 0L) {
                    continue;
                }
                fluidStorages[slot].extract(entry.id(), entry.nbt(), removed);
                if (destroy > 0L) {
                    overflowLogs[slot].record(diagnosticLabel(slot), resource, targets[slot], destroy,
                        KeeperOverflow.excess(net.get(resource) + (left - destroy), targets[slot]));
                }
                changed = true;
            }
        }
        if (changed) {
            setChanged();
        }
    }

    // ==================== 插件槽 ====================

    /** 0=speed，1=stack，2=autocraft，其余 -1。 */
    private static int upgradeKind(final ItemStack stack) {
        final ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
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

    /**
     * 插件槽校验：<b>纯只读 —— 绝不搬移 / 拆分 / 弹出 / 删除任何物品</b>。
     *
     * <p><b>为什么从「即时弹出」改成只读（守恒硬底线，用户实测丢物）</b>：旧实现把「某格多于 1 个」
     * 的冗余部分、以及「非法 / 超上限」的整格用 {@code ejectSlot} / {@code ejectItem}
     * <b>先 {@code removeItemNoUpdate(index)} 从容器里删掉、再生成掉落实体</b>。这条「先删后给」
     * 不是原子操作：只要「给」的那一步落空 —— 掉落实体被生成在 {@code (x+0.5, y+0.5, z+0.5)}，
     * 那正是保持器方块<b>自己所在的实心方块内部</b>（被埋、玩家看不见也捡不到）、
     * 或实体随后按 vanilla 规则 despawn —— 物品就<b>永久离开玩家的世界</b>，玩家看到的就是
     * 「插件直接就没了」。更糟的是它被 <b>inventory 监听器（任何容器改动都跑一次）</b> 与
     * <b>网络节点每 tick</b> 双重触发，于是「一格多个」的状态会被<b>反复弹出直到该格被清空</b>。</p>
     *
     * <p>现在这里<b>只判定、只记账</b>：发现异常打一条节流诊断日志（让玩家在日志里看到原因），
     * <b>一个物品都不动</b>。玩家已经存在的「一格多个 / 非法物品」状态在本次改动后<b>原样保持</b>，
     * 不拆分、不清理、不迁移。</p>
     *
     * <p>「不再出现一格多个」的最小防线在界面层，且它<b>只拒绝放入、不碰已有物品</b>：
     * {@link cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot#mayPlace} 拒收非空槽 +
     * {@code getMaxStackSize()} 恒 1 —— 拒收时物品完整留在调用方手里（光标 / 原槽）。</p>
     */
    public void enforceUpgradeCaps() {
        if (level == null || level.isClientSide() || loadingInventory) {
            return;
        }
        int oversized = 0;
        int invalid = 0;
        final int[] perKind = new int[3]; // 0=speed 1=stack 2=autocraft
        for (int i = SLOT_COUNT; i < inventory.getContainerSize(); i++) {
            final ItemStack inSlot = inventory.getItem(i);
            if (inSlot.isEmpty()) {
                continue;
            }
            final int kind = upgradeKind(inSlot);
            if (kind < 0) {
                invalid++;
                continue;
            }
            if (inSlot.getCount() > 1) {
                oversized++;
            }
            perKind[kind]++;
        }
        noteUpgradeAnomalies(oversized, invalid, perKind);
    }

    /**
     * 异常状态变化时打<b>一条</b>诊断日志（只读：不改任何物品）。
     * <p><b>为什么要有它</b>：界面拒收新升级时玩家需要一个可查的原因；把「异常组合」编成签名并
     * 只在签名变化时输出，既不刷屏，又能从日志里确认「这台机器确实有一格多个 / 非法物品」。
     * 这也是本次「不删除」改动的证据通道。</p>
     */
    private void noteUpgradeAnomalies(final int oversized, final int invalid, final int[] perKind) {
        final boolean anomalous = oversized > 0 || invalid > 0
            || perKind[0] > 6 || perKind[1] > 6 || perKind[2] > 1;
        final String signature = anomalous
            ? oversized + "/" + invalid + "/" + perKind[0] + "/" + perKind[1] + "/" + perKind[2] : "";
        if (signature.equals(lastUpgradeAnomaly)) {
            return;
        }
        lastUpgradeAnomaly = signature;
        if (anomalous) {
            LOGGER.info("[{}] 插件槽异常状态被保留原样（一格多个 / 非法物品 / 超上限）：oversized={} invalid={}"
                + " speed={} stack={} autocraft={} —— 只读校验：不搬移、不拆分、不弹出、不删除",
                diagnosticLabel(), oversized, invalid, perKind[0], perKind[1], perKind[2]);
        }
    }

    public boolean hasAutocraftingUpgrade() {
        return countUpgrades(AUTOCRAFTING_UPGRADE) > 0;
    }

    /**
     * 该槽是否实际执行自动合成：<b>「装了自动合成升级」是前提，槽位自身的开关是细调</b>。
     * <p>没有升级时无论槽位开关如何都不合成；装了升级后，玩家还可以按槽位单独关掉某些槽。</p>
     */
    public boolean shouldAutoCraft(final int slot) {
        return hasAutocraftingUpgrade() && isAutoCraft(slot);
    }

    /** 每秒销毁的过量数量（沿用定量保持器升级公式）。 */
    public int getDestroyRate() {
        final int speed = Math.min(countUpgrades(SPEED_UPGRADE), 6);
        final int stack = Math.min(countUpgrades(STACK_UPGRADE), 6);
        final int power = Math.min(speed + stack * 3, 24);
        return Config.quantityKeeperDestroyRate << power;
    }

    public int getSpeedUpgradeCount() {
        return countUpgrades(SPEED_UPGRADE);
    }

    private int countUpgrades(final ResourceLocation upgradeId) {
        final net.minecraft.world.item.Item upgradeItem = BuiltInRegistries.ITEM.get(upgradeId);
        int count = 0;
        for (int i = SLOT_COUNT; i < inventory.getContainerSize(); i++) {
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

    // ==================== 持久化 ====================

    @Override
    public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put(TAG_INVENTORY, writeInventoryTag(registries));
        writeConfigs(tag);
        // 每槽物品同类存储逐槽序列化
        for (int i = 0; i < SLOT_COUNT; i++) {
            tag.put(TAG_ITEM_STORAGE + i, itemStorages[i].serializeNBT(registries));
        }
        writeFluidStorages(tag);
    }

    @Override
    public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(TAG_INVENTORY)) {
            loadingInventory = true;
            try {
                readInventoryTag(tag, registries);
            } finally {
                loadingInventory = false;
            }
        }
        readConfigs(tag, registries);
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (tag.contains(TAG_ITEM_STORAGE + i)) {
                itemStorages[i].deserializeNBT(registries, tag.getCompound(TAG_ITEM_STORAGE + i));
            }
        }
        readFluidStorages(tag);
        updateBlockedState();
    }

    /**
     * 把界面容器（4 个 ghost 标记槽 + 6 个插件槽）写成<b>带槽位下标</b>的列表。
     *
     * <h2>为什么不能用 {@code SimpleContainer#createTag}</h2>
     * <p>原版的 {@code SimpleContainer#createTag} <b>只写物品、不写 "Slot"</b>，而对应的
     * {@code SimpleContainer#fromTag} 会走 {@code addItem}（先并入同类已有堆、再塞首个空槽）——
     * 于是「放在 4/5/6 三个不同格的堆叠升级」在存盘再读回后会被<b>挤进同一格</b>（而且是靠前的标记槽），
     * 玩家看到的正是「插件被挤成一格」。逐格带 "Slot" 的读写统一收敛到 {@link RsccSlotNbt}
     * （与基础保持器 / 归流缓存仓共用同一份实现），本机不再各写一遍。</p>
     */
    private ListTag writeInventoryTag(final HolderLookup.Provider registries) {
        return RsccSlotNbt.write(inventory, registries);
    }

    /**
     * 读回界面容器：<b>有槽位下标就逐格放回原槽</b>；没有（旧档 / 旧方块物品）才退回原版
     * {@code SimpleContainer#fromTag} 的兼容搬运（只做一次，<b>不丢物</b>，下次存盘即转为新格式）。
     */
    private void readInventoryTag(final CompoundTag tag, final HolderLookup.Provider registries) {
        RsccSlotNbt.read(tag.getList(TAG_INVENTORY, Tag.TAG_COMPOUND), inventory, registries);
    }

    /** 写入 4 槽配置（目标数量 / 自动合成 / 标记形态 / 流体标记 id+nbt）。 */
    private void writeConfigs(final CompoundTag tag) {
        final ListTag list = new ListTag();
        for (int i = 0; i < SLOT_COUNT; i++) {
            final CompoundTag entry = new CompoundTag();
            entry.putInt(TAG_CONFIG_FORM, fluidMarkerIds[i] == null ? FORM_ITEM : fluidForms[i]);
            entry.putString(TAG_CONFIG_FLUID_ID, fluidMarkerIds[i] == null ? "" : fluidMarkerIds[i].toString());
            entry.put(TAG_CONFIG_FLUID_NBT, fluidMarkerNbts[i] == null ? new CompoundTag() : fluidMarkerNbts[i]);
            entry.putInt(TAG_CONFIG_TARGET, targets[i]);
            entry.putBoolean(TAG_CONFIG_AUTOCRAFT, autoCraft[i]);
            entry.putBoolean(TAG_CONFIG_DESTROY_OVERFLOW, destroyOverflow[i]);
            list.add(entry);
        }
        tag.put(TAG_CONFIGS, list);
    }

    private void readConfigs(final CompoundTag tag, final HolderLookup.Provider registries) {
        final ListTag list = tag.getList(TAG_CONFIGS, Tag.TAG_COMPOUND);
        for (int i = 0; i < SLOT_COUNT && i < list.size(); i++) {
            final CompoundTag entry = list.getCompound(i);
            // 下限放宽到 0（0 = 未标记）；旧存档若为 0 也按「未标记」读取
            targets[i] = Math.max(0, entry.getInt(TAG_CONFIG_TARGET));
            // 自动合成开关：<b>缺字段一律按「关」</b>（用户硬要求：放入自动合成升级只让按钮由禁用变可点，
            // 绝不自动打开；旧档没有这个字段时旧实现会回退成「开」，正是「偶发性自动打开」的来源）。
            // 玩家手动打开过的存档会带 Autocraft=true，照常读回（不被默认值覆盖）。
            autoCraft[i] = entry.getBoolean(TAG_CONFIG_AUTOCRAFT);
            destroyOverflow[i] = entry.getBoolean(TAG_CONFIG_DESTROY_OVERFLOW);
            final String rawId = entry.getString(TAG_CONFIG_FLUID_ID);
            if (!rawId.isEmpty()) {
                final ResourceLocation id = ResourceLocation.tryParse(rawId);
                if (id != null) {
                    fluidMarkerIds[i] = id;
                    fluidForms[i] = entry.getInt(TAG_CONFIG_FORM) == FORM_GAS ? FORM_GAS : FORM_FLUID;
                    fluidMarkerNbts[i] = entry.contains(TAG_CONFIG_FLUID_NBT)
                        ? entry.getCompound(TAG_CONFIG_FLUID_NBT) : new CompoundTag();
                }
            }
        }
    }

    private void writeFluidStorages(final CompoundTag tag) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            fluidStorages[i].save(tag, TAG_FLUID_STORAGE + i);
        }
    }

    private void readFluidStorages(final CompoundTag tag) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            fluidStorages[i].load(tag, TAG_FLUID_STORAGE + i);
        }
    }

    /**
     * 破坏方块时写入掉落物物品 NBT 的状态（零损耗）：4 槽配置 + 4 份流体同类存储。
     * <p>物品同类存储与标记 / 插件槽由破坏逻辑按实体掉落，这里不含它们，避免重复。</p>
     */
    public CompoundTag saveStateForItem() {
        stateDropped = true;
        final CompoundTag tag = new CompoundTag();
        writeConfigs(tag);
        writeFluidStorages(tag);
        return tag;
    }

    /** 状态是否已随掉落物 NBT 带走。 */
    public boolean isStateDropped() {
        return stateDropped;
    }

    /** 放置时从掉落物 NBT 回填 4 槽配置 + 流体同类存储（零损耗）。 */
    public void loadPlacedState(final CompoundTag tag, final HolderLookup.Provider registries) {
        if (tag == null || tag.isEmpty()) {
            return;
        }
        readConfigs(tag, registries);
        readFluidStorages(tag);
        updateBlockedState();
        setChanged();
    }

    /** 是否存在任何流体同类存储 / 流体标记（供方块判断是否需要补掉带 NBT 的方块物品）。 */
    public boolean hasAnyFluidState() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (!fluidStorages[i].isEmpty() || hasDirectFluidMarker(i)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 数值同步 ====================

    /** 供菜单同步的实时数据（索引表见类常量 DATA_*）。 */
    public net.minecraft.world.inventory.ContainerData getContainerData() {
        return new net.minecraft.world.inventory.ContainerData() {
            @Override
            public int get(final int index) {
                final int slot = index / DATA_PER_SLOT;
                if (index >= 0 && index < SLOT_COUNT * DATA_PER_SLOT) {
                    return switch (index % DATA_PER_SLOT) {
                        case DATA_SLOT_TARGET -> targets[slot];
                        case DATA_SLOT_FORM -> hasDirectFluidMarker(slot) ? fluidForms[slot] : FORM_ITEM;
                        // 自动合成状态 = 装了升级 且 该槽开关为开（界面上是一个 ✓/✗ 按钮，可单独控制）
                        case DATA_SLOT_AUTOCRAFT -> shouldAutoCraft(slot) ? 1 : 0;
                        case DATA_SLOT_BLOCKED -> blocked[slot] ? 1 : 0;
                        case DATA_SLOT_HAS_MARKER -> hasMarker(slot) ? 1 : 0;
                        case DATA_SLOT_DESTROY_OVERFLOW -> destroyOverflow[slot] ? 1 : 0;
                        default -> 0;
                    };
                }
                return switch (index) {
                    case DATA_SPEED_UPGRADES -> getSpeedUpgradeCount();
                    case DATA_HAS_AUTOCRAFT_UPGRADE -> hasAutocraftingUpgrade() ? 1 : 0;
                    // 红石模式：走 RS 的 RedstoneModeSettings 映射（与 RS 原版机器同一套 0/1/2 编码）
                    case DATA_REDSTONE_MODE -> com.refinedmods.refinedstorage.common.support.RedstoneModeSettings
                        .getRedstoneMode(getRedstoneMode());
                    default -> 0;
                };
            }

            @Override
            public void set(final int index, final int value) {
                // 由服务端逻辑修改
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };
    }

    // ==================== 能力注册 ====================

    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.ADVANCED_QUANTITY_KEEPER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
        event.registerBlockEntity(
            Capabilities.ItemHandler.BLOCK,
            RS_Create_Compat.ADVANCED_QUANTITY_KEEPER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getItemHandler()
        );
        event.registerBlockEntity(
            Capabilities.FluidHandler.BLOCK,
            RS_Create_Compat.ADVANCED_QUANTITY_KEEPER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getFluidHandler()
        );
    }

    // ==================== 网络同类资源辅助 ====================

    private StorageNetworkComponent storageComponent() {
        final Network network = mainNetworkNode.getNetworkOrNull();
        return network == null ? null : network.getComponent(StorageNetworkComponent.class);
    }

    /** 网络内与第 {@code slot} 槽物品标记同类的存量。 */
    private long networkItemAmount(final int slot) {
        final ItemStack marker = inventory.getItem(slot);
        if (marker.isEmpty() || fluidMarkerIds[slot] != null || level == null || level.isClientSide()) {
            return 0L;
        }
        final StorageNetworkComponent net = storageComponent();
        return net == null ? 0L : net.get(ItemResource.ofItemStack(marker));
    }

    private long extractNetworkItem(final int slot, final long amount, final boolean simulate) {
        if (amount <= 0) {
            return 0L;
        }
        final ItemStack marker = inventory.getItem(slot);
        if (marker.isEmpty() || fluidMarkerIds[slot] != null) {
            return 0L;
        }
        final StorageNetworkComponent net = storageComponent();
        if (net == null) {
            return 0L;
        }
        return net.extract(ItemResource.ofItemStack(marker), amount,
            simulate ? Action.SIMULATE : Action.EXECUTE, Actor.EMPTY);
    }

    private long extractNetworkFluid(final int slot, final ResourceLocation id, final CompoundTag nbt,
                                     final long amount, final boolean simulate) {
        if (amount <= 0 || !matchesFluidMarker(slot, id)) {
            return 0L;
        }
        final Fluid fluid = BuiltInRegistries.FLUID.get(id);
        if (fluid == null || fluid == Fluids.EMPTY) {
            return 0L;
        }
        final StorageNetworkComponent net = storageComponent();
        if (net == null) {
            return 0L;
        }
        final HolderLookup.Provider registries = level == null ? null : level.registryAccess();
        final FluidResource resource = new FluidResource(fluid, MarkerEntry.decodeComponents(nbt, registries));
        return net.extract(resource, amount, simulate ? Action.SIMULATE : Action.EXECUTE, Actor.EMPTY);
    }

    /** 找到接受该物品的配置槽（只匹配已标记的物品，空槽/流体槽不匹配）；无则 -1。 */
    private int matchingItemSlot(final ItemStack stack) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (matchesItemMarker(i, stack)) {
                return i;
            }
        }
        return -1;
    }

    /** 找到接受该流体的配置槽；无则 -1。 */
    private int matchingFluidSlot(final ResourceLocation id) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (matchesFluidMarker(i, id)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 聚合物品能力：每槽 {@link #STORAGE_SLOTS} 格本机存储 + 每槽一个「网络同类资源」虚拟末槽。
     * <ul>
     *     <li>插入：只有匹配某个已标记槽的物品才被接收，落入该槽存储；
     *     其它物品（含空槽未标记类型）一律原样退回，绝不销毁；</li>
     *     <li>取出：本机优先，虚拟槽从网络取。</li>
     * </ul>
     */
    private final class KeeperItemHandler implements net.neoforged.neoforge.items.IItemHandler {
        private static final int LOCAL = SLOT_COUNT * STORAGE_SLOTS;

        @Override
        public int getSlots() {
            return LOCAL + SLOT_COUNT;
        }

        @Override
        public ItemStack getStackInSlot(final int slot) {
            if (slot >= 0 && slot < LOCAL) {
                return itemStorages[slot / STORAGE_SLOTS].getStackInSlot(slot % STORAGE_SLOTS);
            }
            if (slot >= LOCAL && slot < LOCAL + SLOT_COUNT) {
                final int config = slot - LOCAL;
                final long amount = networkItemAmount(config);
                if (amount <= 0) {
                    return ItemStack.EMPTY;
                }
                return inventory.getItem(config).copyWithCount((int) Math.min(STORAGE_SLOT_LIMIT, amount));
            }
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(final int slot, final ItemStack stack, final boolean simulate) {
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            final int target = matchingItemSlot(stack);
            if (target < 0) {
                return stack; // 只接受已标记类型：不匹配原样退回（堵塞，不销毁）
            }
            ItemStack remainder = stack;
            final ItemStackHandler storage = itemStorages[target];
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
            if (slot >= 0 && slot < LOCAL) {
                final int config = slot / STORAGE_SLOTS;
                final ItemStack local = itemStorages[config].getStackInSlot(slot % STORAGE_SLOTS);
                if (!local.isEmpty()) {
                    return itemStorages[config].extractItem(slot % STORAGE_SLOTS, amount, simulate);
                }
                return networkItem(config, amount, simulate);
            }
            if (slot >= LOCAL && slot < LOCAL + SLOT_COUNT) {
                return networkItem(slot - LOCAL, amount, simulate);
            }
            return ItemStack.EMPTY;
        }

        private ItemStack networkItem(final int config, final int amount, final boolean simulate) {
            final long taken = extractNetworkItem(config, amount, simulate);
            return taken <= 0 ? ItemStack.EMPTY
                : inventory.getItem(config).copyWithCount((int) Math.min(Integer.MAX_VALUE, taken));
        }

        @Override
        public int getSlotLimit(final int slot) {
            return STORAGE_SLOT_LIMIT;
        }

        @Override
        public boolean isItemValid(final int slot, final ItemStack stack) {
            return matchingItemSlot(stack) >= 0;
        }
    }

    /**
     * 聚合流体能力（每槽一个 tank）：
     * <ul>
     *     <li>{@code fill} 只接受与某槽标记相同的流体（不匹配返回 0，上游自行回压，绝不销毁）；</li>
     *     <li>{@code drain} 任意已存条目都可取出（含标记变更后遗留的不匹配流体）。</li>
     * </ul>
     */
    private final class KeeperFluidHandler implements IFluidHandler {
        @Override
        public int getTanks() {
            return SLOT_COUNT;
        }

        @Override
        public FluidStack getFluidInTank(final int tank) {
            final List<MultiFluidCache.Entry> entries = fluidStorages[tank].entries();
            if (entries.isEmpty()) {
                return FluidStack.EMPTY;
            }
            return toStack(entries.get(0));
        }

        @Override
        public int getTankCapacity(final int tank) {
            return (int) Math.min(Integer.MAX_VALUE, FLUID_STORAGE_CAPACITY);
        }

        @Override
        public boolean isFluidValid(final int tank, final FluidStack stack) {
            return stack != null && !stack.isEmpty()
                && matchesFluidMarker(tank, BuiltInRegistries.FLUID.getKey(stack.getFluid()));
        }

        @Override
        public int fill(final FluidStack resource, final FluidAction action) {
            if (resource == null || resource.isEmpty()) {
                return 0;
            }
            final ResourceLocation id = BuiltInRegistries.FLUID.getKey(resource.getFluid());
            final int tank = matchingFluidSlot(id);
            if (tank < 0) {
                return 0; // 只接受已标记类型：不匹配返回 0（上游回压，不销毁）
            }
            final CompoundTag nbt = MarkerEntry.encodeComponents(
                resource.getComponentsPatch(), level == null ? null : level.registryAccess());
            final long accepted = action.simulate()
                ? Math.min(fluidStorages[tank].getFreeSpace(), resource.getAmount())
                : fluidStorages[tank].insert(id, nbt, resource.getAmount());
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
            final int tank = matchingFluidSlot(id);
            if (tank >= 0) {
                final long local = Math.min(resource.getAmount(), fluidStorages[tank].getAmount(id, nbt));
                if (local > 0) {
                    return drainById(tank, id, nbt, local, action);
                }
                final long fromNetwork = extractNetworkFluid(tank, id, nbt, resource.getAmount(), action.simulate());
                if (fromNetwork > 0) {
                    return new FluidStack(resource.getFluid(),
                        (int) Math.min(Integer.MAX_VALUE, fromNetwork));
                }
            }
            // 标记已变更的遗留流体：只有在任意槽里真的存在时才可 drain
            for (int i = 0; i < SLOT_COUNT; i++) {
                final long local = Math.min(resource.getAmount(), fluidStorages[i].getAmount(id, nbt));
                if (local > 0) {
                    return drainById(i, id, nbt, local, action);
                }
            }
            return FluidStack.EMPTY;
        }

        @Override
        public FluidStack drain(final int maxDrain, final FluidAction action) {
            if (maxDrain <= 0) {
                return FluidStack.EMPTY;
            }
            for (int i = 0; i < SLOT_COUNT; i++) {
                final List<MultiFluidCache.Entry> entries = fluidStorages[i].entries();
                if (!entries.isEmpty()) {
                    final MultiFluidCache.Entry entry = entries.get(0);
                    return drainById(i, entry.id(), entry.nbt(), Math.min(maxDrain, entry.amount()), action);
                }
            }
            return FluidStack.EMPTY;
        }

        private FluidStack drainById(final int tank, final ResourceLocation id, final CompoundTag nbt,
                                     final long amount, final FluidAction action) {
            if (amount <= 0) {
                return FluidStack.EMPTY;
            }
            final Fluid fluid = BuiltInRegistries.FLUID.get(id);
            if (fluid == null || fluid == Fluids.EMPTY) {
                return FluidStack.EMPTY;
            }
            final long extracted = action.simulate() ? amount : fluidStorages[tank].extract(id, nbt, amount);
            if (!action.simulate() && extracted > 0) {
                setChanged();
            }
            return new FluidStack(fluid, (int) Math.min(Integer.MAX_VALUE, extracted));
        }

        private FluidStack toStack(final MultiFluidCache.Entry entry) {
            final Fluid fluid = BuiltInRegistries.FLUID.get(entry.id());
            if (fluid == null || fluid == Fluids.EMPTY) {
                return FluidStack.EMPTY;
            }
            return new FluidStack(fluid, (int) Math.min(Integer.MAX_VALUE, entry.amount()));
        }
    }
}
