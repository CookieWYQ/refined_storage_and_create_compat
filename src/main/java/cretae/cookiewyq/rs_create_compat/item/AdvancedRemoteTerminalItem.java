package cretae.cookiewyq.rs_create_compat.item;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.energy.EnergyStorage;
import com.refinedmods.refinedstorage.api.network.impl.energy.EnergyStorageImpl;
import com.refinedmods.refinedstorage.common.Platform;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.grid.Grid;
import com.refinedmods.refinedstorage.common.api.support.HelpTooltipComponent;
import com.refinedmods.refinedstorage.common.api.support.energy.AbstractNetworkEnergyItem;
import com.refinedmods.refinedstorage.common.api.support.network.item.NetworkItemContext;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.content.Items;
import com.refinedmods.refinedstorage.common.grid.GridData;
import com.refinedmods.refinedstorage.common.support.containermenu.ExtendedMenuProvider;
import cretae.cookiewyq.rs_create_compat.Config;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 高级远程多功能终端（参照 Universal-Grid 的实现方式）：
 * <ul>
 *     <li>右键使用 = 直接打开 RS 原版无线终端界面（合成终端 / 样板终端 / 合成仓管理 / 合成仓监视 / 序列装配），
 *     物品可正常存取。</li>
 *     <li>模式切换：客户端在打开的 RS 界面上渲染右下角方块图标 Tab，点击发送 C2S 包，
 *     服务端把模式写回物品并重开对应模式界面（旧界面自动关闭）。</li>
 *     <li>普通版需要电量（没电时界面打开但操作禁用，与原版一致）；满电版默认满电；创造版无视电量。</li>
 * </ul>
 */
public class AdvancedRemoteTerminalItem extends AbstractNetworkEnergyItem {
    public static final String TAG_MODE = "Mode";
    public static final int MODE_GRID = 0;
    public static final int MODE_PATTERNS = 1;
    public static final int MODE_MANAGER = 2;
    public static final int MODE_MONITOR = 3;
    public static final int MODE_SEQUENCE = 4;
    public static final int MODE_COUNT = 5;

    private static final Logger LOGGER = LoggerFactory.getLogger(AdvancedRemoteTerminalItem.class);
    private static final String RS_PATTERN_GRID_MENU =
        "com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternGridContainerMenu";
    private static final String RS_PATTERN_GRID_DATA =
        "com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternGridData";
    private static final String RS_PATTERN_TYPE =
        "com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternType";
    private static final String RS_PROCESSING_INPUT_DATA =
        "com.refinedmods.refinedstorage.common.autocrafting.patterngrid.ProcessingInputData";
    private static final String RS_RESOURCE_CONTAINER_DATA =
        "com.refinedmods.refinedstorage.common.support.resource.ResourceContainerData";
    private static final String RS_MANAGER_MENU =
        "com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager.AutocrafterManagerContainerMenu";
    private static final String RS_MANAGER_DATA =
        "com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager.AutocrafterManagerData";

    public enum Type {
        /** 普通版：需要电量，没电时界面禁用操作。 */
        NORMAL,
        /** 满电版：默认满电。 */
        CHARGED,
        /** 创造版：无视电量。 */
        CREATIVE
    }

    private final Type type;

    public AdvancedRemoteTerminalItem(final Type type) {
        super(
            new Item.Properties().stacksTo(1).fireResistant(),
            RefinedStorageApi.INSTANCE.getEnergyItemHelper(),
            RefinedStorageApi.INSTANCE.getNetworkItemHelper()
        );
        this.type = type;
    }

    public boolean isCreative() {
        return type == Type.CREATIVE;
    }

    public static int getMode(final ItemStack stack) {
        return stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY).getUnsafe().getInt(TAG_MODE);
    }

    public static void setMode(final ItemStack stack, final int mode) {
        stack.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY,
            data -> data.update(tag -> tag.putInt(TAG_MODE, mode)));
    }

    @Override
    public ItemStack getDefaultInstance() {
        // 满电版 / 创造版默认满电（直接写入 RS 能量数据组件，最可靠）；
        // 普通版默认无电
        final ItemStack stack = super.getDefaultInstance();
        if (type != Type.NORMAL) {
            final long capacity = type == Type.CREATIVE
                ? Integer.MAX_VALUE
                : Config.advancedRemoteTerminalEnergyCapacity;
            stack.set(com.refinedmods.refinedstorage.common.content.DataComponents.INSTANCE.getEnergy(), capacity);
        }
        return stack;
    }

    // —— 创造版：不显示物品栏底部的电量条 ——
    @Override
    public boolean isBarVisible(final ItemStack stack) {
        return !isCreative() && super.isBarVisible(stack);
    }

    @Override
    public int getBarWidth(final ItemStack stack) {
        if (isCreative()) {
            return 0;
        }
        return super.getBarWidth(stack);
    }

    @Override
    public int getBarColor(final ItemStack stack) {
        if (isCreative()) {
            return 0x00000000;
        }
        return super.getBarColor(stack);
    }

    public EnergyStorage createEnergyStorage(final ItemStack stack) {
        final long capacity = type == Type.CREATIVE
            ? Integer.MAX_VALUE
            : Config.advancedRemoteTerminalEnergyCapacity;
        return RefinedStorageApi.INSTANCE.asItemEnergyStorage(new EnergyStorageImpl(capacity), stack);
    }

    @Override
    public void appendHoverText(final ItemStack stack,
                                final TooltipContext context,
                                final List<Component> tooltip,
                                final TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
    }

    @Override
    public Optional<TooltipComponent> getTooltipImage(final ItemStack stack) {
        return Optional.of(new HelpTooltipComponent(
            isBound(stack)
                ? Component.translatable("item.rs_create_compat.advanced_remote_terminal.usage")
                : Component.translatable("item.rs_create_compat.advanced_remote_terminal.bind_hint")
        ));
    }

    @Override
    protected void use(@Nullable final Component name,
                       final ServerPlayer player,
                       final SlotReference slotReference,
                       final NetworkItemContext context) {
        final Optional<ItemStack> stackOpt = slotReference.resolve(player);
        if (stackOpt.isEmpty()) {
            return;
        }
        // 仿 RS 原版：不检查网络绑定与电量，总是打开界面；未绑定/没电时界面内操作禁用（由 RS 原版机制处理）。
        openModeScreen(player, stackOpt.get(), slotReference);
    }

    /** 根据物品当前模式打开对应 RS 原版界面。 */
    public void openModeScreen(final ServerPlayer player, final ItemStack stack, final SlotReference slotReference) {
        final int mode = getMode(stack);
        final Component title = Objects.requireNonNullElse(
            stack.getHoverName(),
            Component.translatable("item.rs_create_compat.advanced_remote_terminal")
        );
        try {
            switch (mode) {
                case MODE_MONITOR -> Items.INSTANCE.getWirelessAutocraftingMonitor()
                    .use(player, stack, slotReference);
                case MODE_PATTERNS -> openPatternGrid(player, stack, slotReference, title);
                case MODE_MANAGER -> openAutocrafterManager(player, stack, slotReference, title);
                case MODE_SEQUENCE -> openPatternGrid(player, stack, slotReference, title);
                default -> Items.INSTANCE.getWirelessGrid().use(player, stack, slotReference);
            }
        } catch (final Throwable t) {
            LOGGER.error("Failed to open terminal mode {}", mode, t);
            player.displayClientMessage(
                Component.translatable("item.rs_create_compat.advanced_remote_terminal.mode_failed"),
                true);
        }
    }

    // ========== 模式界面打开 ==========

    /** MODE 0：合成终端（RS 原版无线合成终端）。直接复用，物品可存取。 */

    /** MODE 1 / MODE 4：样板终端 / 序列装配（RS 原版样板终端界面，data 版构造）。 */
    private void openPatternGrid(final ServerPlayer player,
                                 final ItemStack stack,
                                 final SlotReference slotReference,
                                 final Component title) throws ReflectiveOperationException {
        final NetworkItemContext context = RefinedStorageApi.INSTANCE.getNetworkItemHelper()
            .createContext(stack, player, slotReference);
        // WirelessGrid 为 RS 包私有类，无法直接 new，使用反射构造（构造器仅接受 NetworkItemContext）
        final Class<?> wirelessGridClass = Class.forName(
            "com.refinedmods.refinedstorage.common.grid.WirelessGrid");
        final Constructor<?> wirelessGridCtor = wirelessGridClass.getDeclaredConstructor(NetworkItemContext.class);
        wirelessGridCtor.setAccessible(true);
        final Grid grid = (Grid) wirelessGridCtor.newInstance(context);

        final Object gridData = GridData.of(grid);

        // PatternGridData(GridData, PatternType, ProcessingInputData, ResourceContainerData, int)
        final Class<?> patternTypeClass = Class.forName(RS_PATTERN_TYPE);
        final Object patternType = patternTypeClass.getEnumConstants()[0]; // CRAFTING
        final Object processingInput = createEmptyProcessingInputData();
        final Object processingOutput = createEmptyResourceContainerData();
        final Class<?> dataClass = Class.forName(RS_PATTERN_GRID_DATA);
        final Constructor<?> dataCtor = dataClass.getDeclaredConstructor(
            gridDataClass(), patternTypeClass,
            Class.forName(RS_PROCESSING_INPUT_DATA),
            Class.forName(RS_RESOURCE_CONTAINER_DATA),
            int.class
        );
        dataCtor.setAccessible(true);
        final Object data = dataCtor.newInstance(gridData, patternType, processingInput, processingOutput, -1);

        openReflectedMenu(player, RS_PATTERN_GRID_MENU, dataClass, data, title);
    }

    private static Class<?> gridDataClass() {
        return GridData.class;
    }

    private Object createEmptyProcessingInputData() throws ReflectiveOperationException {
        final Object emptyContainer = createEmptyResourceContainerData();
        final Class<?> clazz = Class.forName(RS_PROCESSING_INPUT_DATA);
        final Constructor<?> ctor = clazz.getDeclaredConstructor(
            Class.forName(RS_RESOURCE_CONTAINER_DATA), java.util.List.class);
        ctor.setAccessible(true);
        return ctor.newInstance(emptyContainer, java.util.List.of());
    }

    private Object createEmptyResourceContainerData() throws ReflectiveOperationException {
        final Class<?> clazz = Class.forName(RS_RESOURCE_CONTAINER_DATA);
        final Constructor<?> ctor = clazz.getDeclaredConstructor(java.util.List.class);
        ctor.setAccessible(true);
        return ctor.newInstance(java.util.List.of());
    }

    /** MODE 2：合成仓管理（RS 原版合成仓管理界面，data 版构造）。 */
    private void openAutocrafterManager(final ServerPlayer player,
                                        final ItemStack stack,
                                        final SlotReference slotReference,
                                        final Component title) throws ReflectiveOperationException {
        final Class<?> dataClass = Class.forName(RS_MANAGER_DATA);
        final Constructor<?> dataCtor = dataClass.getDeclaredConstructor(java.util.List.class, boolean.class);
        dataCtor.setAccessible(true);
        final Object data = dataCtor.newInstance(java.util.List.of(), true);
        openReflectedMenu(player, RS_MANAGER_MENU, dataClass, data, title);
    }

    /** 用 RS 原版 MenuType + StreamCodec 打开 data 版菜单（客户端自动渲染对应原版 Screen）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void openReflectedMenu(final ServerPlayer player,
                                          final String menuClassName,
                                          final Class<?> dataClass,
                                          final Object data,
                                          final Component title) throws ReflectiveOperationException {
        final Class<?> menuClass = Class.forName(menuClassName);
        final Constructor<?> menuCtor = menuClass.getDeclaredConstructor(
            int.class, net.minecraft.world.entity.player.Inventory.class, dataClass);
        menuCtor.setAccessible(true);
        final java.lang.reflect.Field streamCodecField = dataClass.getField("STREAM_CODEC");
        final Object streamCodec = streamCodecField.get(null);

        final ExtendedMenuProvider<Object> provider = new ExtendedMenuProvider<>() {
            @Override
            public Object getMenuData() {
                return data;
            }

            @Override
            public StreamCodec<RegistryFriendlyByteBuf, Object> getMenuCodec() {
                return (StreamCodec) streamCodec;
            }

            @Override
            public Component getDisplayName() {
                return title;
            }

            @Nullable
            @Override
            public AbstractContainerMenu createMenu(final int syncId,
                                                    final net.minecraft.world.entity.player.Inventory inv,
                                                    final net.minecraft.world.entity.player.Player p) {
                try {
                    return (AbstractContainerMenu) menuCtor.newInstance(syncId, inv, data);
                } catch (final ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        Platform.INSTANCE.getMenuOpener().openMenu(player, provider);
    }

    /** MODE 3：合成仓监视（RS 原版无线自动合成监视器）。直接复用。 */
}
