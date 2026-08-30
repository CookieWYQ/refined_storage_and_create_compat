package cretae.cookiewyq.rs_create_compat.item;

import com.refinedmods.refinedstorage.api.network.energy.EnergyStorage;
import com.refinedmods.refinedstorage.api.network.impl.energy.EnergyStorageImpl;
import com.refinedmods.refinedstorage.common.Platform;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.support.HelpTooltipComponent;
import com.refinedmods.refinedstorage.common.api.support.energy.AbstractNetworkEnergyItem;
import com.refinedmods.refinedstorage.common.api.support.network.item.NetworkItemContext;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.content.Items;
import com.refinedmods.refinedstorage.common.support.containermenu.ExtendedMenuProvider;
import cretae.cookiewyq.rs_create_compat.Config;
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
        // 初始电量由 getDefaultInstance 写入的能量组件决定（满电/创造版默认满电，普通版无电）
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

    /**
     * 该模式是否有对应的 RS 原版无线界面可复用。
     * 样板终端 / 合成仓管理器通过"虚拟方块实体"在服务端构造 RS 原版菜单（客户端仍走 data 版重建）。
     */
    public static boolean isModeSupported(final int mode) {
        return mode == MODE_GRID || mode == MODE_MONITOR || mode == MODE_PATTERNS || mode == MODE_MANAGER;
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
                case MODE_SEQUENCE ->
                    player.displayClientMessage(
                        Component.translatable("item.rs_create_compat.advanced_remote_terminal.mode_unavailable"),
                        true);
                default -> Items.INSTANCE.getWirelessGrid().use(player, stack, slotReference);
            }
        } catch (final Throwable t) {
            LOGGER.error("Failed to open terminal mode {}", mode, t);
            player.displayClientMessage(
                Component.translatable("item.rs_create_compat.advanced_remote_terminal.mode_failed"),
                true);
        }
    }

    // ========== 虚拟方块实体：在服务端内存中构造 RS 原版菜单 ==========

    private static final String RS_PATTERN_GRID_MENU =
        "com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternGridContainerMenu";
    private static final String RS_PATTERN_GRID_BE =
        "com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternGridBlockEntity";
    private static final String RS_MANAGER_MENU =
        "com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager.AutocrafterManagerContainerMenu";
    private static final String RS_MANAGER_BE =
        "com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager.AutocrafterManagerBlockEntity";

    /** MODE 1：样板终端（虚拟 PatternGridBlockEntity + RS 原版 BlockEntity 版菜单）。 */
    private void openPatternGrid(final ServerPlayer player,
                                 final ItemStack stack,
                                 final SlotReference slotReference,
                                 final Component title) throws ReflectiveOperationException {
        final Class<?> beClass = Class.forName(RS_PATTERN_GRID_BE);
        final java.lang.reflect.Constructor<?> beCtor = beClass.getDeclaredConstructor(
            net.minecraft.core.BlockPos.class, net.minecraft.world.level.block.state.BlockState.class);
        beCtor.setAccessible(true);
        // 虚拟方块实体：不放置世界，仅承载容器与网络节点
        final Object be = beCtor.newInstance(
            net.minecraft.core.BlockPos.ZERO,
            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        // 设置虚拟 BE 的 level（配方矩阵/切石机需要 getLevel() 非 null）
        setVirtualBeLevel(be, player.level());

        // 绑定终端解析出的 RS 网络到虚拟 BE 的节点，使其 Grid 方法可访问网络存储
        bindNetworkToVirtualBe(be, stack, player, slotReference);

        final Class<?> menuClass = Class.forName(RS_PATTERN_GRID_MENU);
        final java.lang.reflect.Constructor<?> menuCtor = menuClass.getDeclaredConstructor(
            int.class, net.minecraft.world.entity.player.Inventory.class, beClass);
        menuCtor.setAccessible(true);

        final Class<?> dataClass = Class.forName(
            "com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternGridData");
        final java.lang.reflect.Field streamCodecField = dataClass.getField("STREAM_CODEC");
        final Object streamCodec = streamCodecField.get(null);

        openReflectedMenu(player, be, dataClass, streamCodec, title, menuCtor,
            new java.lang.Object[]{});
    }

    /** MODE 2：合成仓管理（虚拟 AutocrafterManagerBlockEntity + RS 原版 BlockEntity 版菜单）。 */
    private void openAutocrafterManager(final ServerPlayer player,
                                        final ItemStack stack,
                                        final SlotReference slotReference,
                                        final Component title) throws ReflectiveOperationException {
        final Class<?> beClass = Class.forName(RS_MANAGER_BE);
        final java.lang.reflect.Constructor<?> beCtor = beClass.getDeclaredConstructor(
            net.minecraft.core.BlockPos.class, net.minecraft.world.level.block.state.BlockState.class);
        beCtor.setAccessible(true);
        final Object be = beCtor.newInstance(
            net.minecraft.core.BlockPos.ZERO,
            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        setVirtualBeLevel(be, player.level());

        // 绑定网络（管理器按网络中的自动合成仓分组显示）
        bindNetworkToVirtualBe(be, stack, player, slotReference);

        final Class<?> menuClass = Class.forName(RS_MANAGER_MENU);
        // AutocrafterManagerContainerMenu(int, Inventory, AutocrafterManagerBlockEntity, List<Group>)
        final java.lang.reflect.Constructor<?> menuCtor = menuClass.getDeclaredConstructor(
            int.class, net.minecraft.world.entity.player.Inventory.class, beClass, java.util.List.class);
        menuCtor.setAccessible(true);

        final Class<?> dataClass = Class.forName(
            "com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager.AutocrafterManagerData");
        final java.lang.reflect.Field streamCodecField = dataClass.getField("STREAM_CODEC");
        final Object streamCodec = streamCodecField.get(null);

        // 管理器菜单需要 groups 参数（服务端用真实 groups，客户端由 data 同步）
        final Class<?> groupClass = Class.forName(
            "com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager.AutocrafterManagerData$Group");
        final java.lang.Object[] extraArgs = {java.util.List.of()};
        openReflectedMenu(player, be, dataClass, streamCodec, title, menuCtor, extraArgs);
    }

    /** 设置虚拟方块实体的 level（BlockEntity.setLevel 是 public）。 */
    private static void setVirtualBeLevel(final Object be, final net.minecraft.world.level.Level level) {
        try {
            final java.lang.reflect.Method setLevel =
                net.minecraft.world.level.block.entity.BlockEntity.class.getMethod("setLevel",
                    net.minecraft.world.level.Level.class);
            setLevel.setAccessible(true);
            setLevel.invoke(be, level);
        } catch (final ReflectiveOperationException e) {
            // 忽略：没有 level 也能打开大部分界面
        }
    }

    /** 将终端解析到的 RS 网络绑定到虚拟方块实体的 mainNetworkNode（反射访问 protected 字段）。 */
    private static void bindNetworkToVirtualBe(final Object be,
                                               final ItemStack stack,
                                               final ServerPlayer player,
                                               final SlotReference slotReference) throws ReflectiveOperationException {
        final NetworkItemContext context = RefinedStorageApi.INSTANCE.getNetworkItemHelper()
            .createContext(stack, player, slotReference);
        final var networkOpt = context.resolveNetwork();
        if (networkOpt.isEmpty()) {
            return; // 未绑定网络：界面打开但网络相关部分为空
        }
        final Class<?> beClass = be.getClass();
        java.lang.reflect.Field nodeField = null;
        // 向上查找 mainNetworkNode 字段（可能在父类）
        Class<?> c = beClass;
        while (c != null && nodeField == null) {
            try {
                nodeField = c.getDeclaredField("mainNetworkNode");
            } catch (final NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        if (nodeField == null) {
            return;
        }
        nodeField.setAccessible(true);
        final Object node = nodeField.get(be);
        if (node == null) {
            return;
        }
        final java.lang.reflect.Method setNetwork = node.getClass().getMethod("setNetwork",
            com.refinedmods.refinedstorage.api.network.Network.class);
        setNetwork.setAccessible(true);
        setNetwork.invoke(node, networkOpt.get());
        // 标记节点激活
        final java.lang.reflect.Method setActive = node.getClass().getMethod("setActive", boolean.class);
        setActive.setAccessible(true);
        setActive.invoke(node, true);
    }

    /** 用 RS 原版 BlockEntity 版 Menu 构造器打开（服务端真实构造，客户端由 data codec 重建）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void openReflectedMenu(final ServerPlayer player,
                                          final Object be,
                                          final Class<?> dataClass,
                                          final Object streamCodec,
                                          final Component title,
                                          final java.lang.reflect.Constructor<?> menuCtor,
                                          final java.lang.Object[] extraMenuArgs) {
        final ExtendedMenuProvider<Object> provider = new ExtendedMenuProvider<>() {
            @Override
            public Object getMenuData() {
                // 服务端打开时客户端会要求 getMenuData() 构造客户端菜单
                try {
                    final java.lang.reflect.Method getMenuData = be.getClass().getMethod("getMenuData");
                    return getMenuData.invoke(be);
                } catch (final ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
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
                    final java.lang.Object[] args = new java.lang.Object[2 + extraMenuArgs.length];
                    args[0] = syncId;
                    args[1] = inv;
                    System.arraycopy(extraMenuArgs, 0, args, 2, extraMenuArgs.length);
                    return (AbstractContainerMenu) menuCtor.newInstance(args);
                } catch (final ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        Platform.INSTANCE.getMenuOpener().openMenu(player, provider);
    }
}


