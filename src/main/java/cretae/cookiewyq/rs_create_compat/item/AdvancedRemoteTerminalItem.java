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
import javax.annotation.ParametersAreNonnullByDefault;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
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
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class AdvancedRemoteTerminalItem extends AbstractNetworkEnergyItem {
    public static final String TAG_MODE = "Mode";
    /** 已废弃的「额外槽位」旧数据键（仅用于一次性抢救旧存档内容，见 {@link #rescueLegacyExtraSlot}）。 */
    private static final String TAG_LEGACY_EXTRA_SLOT = "RsccExtraSlot";
    /**
     * 序列装配样板终端（{@link #MODE_SEQUENCE}）内容在终端<b>物品</b>上的存档键。
     * <p>该模式没有世界里的方块实体（虚拟方块实体只活在内存里），因此必须把终端 NBT 存进物品的
     * {@code CustomData}：开界面读回、关界面写回。玩家放进终端自己槽位的样板 / 单元样板才不会随
     * 临时对象一起消失（= 不销毁物品），且内容跟着终端物品走（与放下方块等价）。</p>
     */
    private static final String TAG_SEQUENCE_TERMINAL = "RsccSequenceTerminal";
    public static final int MODE_GRID = 0;
    public static final int MODE_PATTERNS = 1;
    public static final int MODE_MANAGER = 2;
    public static final int MODE_MONITOR = 3;
    public static final int MODE_SEQUENCE = 4;
    /** 模式 5：单元样板管理舱（本模组自建界面；按执行舱分组管理单元样板 + 跨仓内存合并视图）。 */
    public static final int MODE_UNIT_MANAGER = 5;
    public static final int MODE_COUNT = 6;

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
        // 不覆写能量：普通版默认无电（与 RS 原版一致）。
        // 满电版通过创造栏的 createAtEnergyCapacity() 生成满电实例（复用 RS 原版机制），
        // 避免与普通版（组件缺失=没电）混淆。
        return super.getDefaultInstance();
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
        if (type == Type.CREATIVE) {
            // 创造版：无视电量，直接返回满电存储（不参与组件读写/充电）
            final EnergyStorageImpl creative = new EnergyStorageImpl(Integer.MAX_VALUE);
            creative.receive(Integer.MAX_VALUE, com.refinedmods.refinedstorage.api.core.Action.EXECUTE);
            return creative;
        }
        // 普通版 / 满电版：与 RS 原版完全一致，电量由物品能量组件决定（asItemEnergyStorage 读写组件）。
        // 满电版在创造栏通过 createAtEnergyCapacity() 生成组件已满的实例；普通版默认无电。
        final long capacity = Config.advancedRemoteTerminalEnergyCapacity;
        return RefinedStorageApi.INSTANCE.asItemEnergyStorage(new EnergyStorageImpl(capacity), stack);
    }

    @Override
    public void appendHoverText(final ItemStack stack,
                                final TooltipContext context,
                                final List<Component> tooltip,
                                final TooltipFlag flag) {
        if (!isCreative()) {
            // 创造版：不显示电量/容量信息（普通版照旧由父类追加）
            super.appendHoverText(stack, context, tooltip, flag);
        }
    }

    @Override
    public Optional<TooltipComponent> getTooltipImage(final ItemStack stack) {
        // 恢复 RS 原生「常显帮助」：用法 / 绑定提示由 HelpTooltipComponent 一直显示，不再走按键分层。
        return Optional.of(new HelpTooltipComponent(
            isBound(stack)
                ? Component.translatable("item.rs_create_compat.advanced_remote_terminal.usage")
                : Component.translatable("item.rs_create_compat.advanced_remote_terminal.bind_hint")
        ));
    }

    /**
     * 手持右键使用（<b>客户端与服务端都会调用</b>，见 RS {@code AbstractNetworkEnergyItem#use}）：
     * <ul>
     *     <li><b>客户端</b>：置位「打开意图」标记 —— 只有由本模组终端打开的界面才叠加右下角模式切换按钮
     *     （回归修复，见 {@link cretae.cookiewyq.rs_create_compat.client.TerminalOpenIntent}）。</li>
     *     <li><b>服务端</b>：交给父类解析出手持引用并走到 {@link #useFromShortcut}，服务端权威地打开界面。</li>
     * </ul>
     */
    @Override
    public InteractionResultHolder<ItemStack> use(final Level level, final Player player, final InteractionHand hand) {
        if (level.isClientSide()) {
            // 只在客户端置位：这是纯客户端状态（决定要不要画那一排按钮），服务端不需要也不该有
            cretae.cookiewyq.rs_create_compat.client.TerminalOpenIntent.mark();
        }
        return super.use(level, player, hand);
    }

    /**
     * 打开界面的<b>唯一服务端实现</b>（手持右键、RS 原生引用链路、本模组快捷键 C2S 包三条链路共用）：
     * <ol>
     *     <li>按引用取出终端的<b>存活栈</b>（引用失效 → 什么都不做）；</li>
     *     <li>一次性抢救旧版「额外槽位」残留内容；</li>
     *     <li>打开物品当前模式对应的界面。</li>
     * </ol>
     * <p><b>为什么把入口提成 public</b>：快捷键改走本模组自己的 C2S 包后，服务端需要按固定优先级
     * 找到终端再打开它；若让网络包去反射 / 触碰 protected 的 {@code use(...)}，等于把物品内部逻辑
     * 暴露成跨包调用。这里给一个语义明确的 public 门面，三版终端（普通 / 满电 / 创造）与三条链路
     * 共用同一实现，不可能出现「某个型号能开、另一个不能」的分叉。</p>
     */
    public void useFromShortcut(final ServerPlayer player, final SlotReference slotReference) {
        final Optional<ItemStack> stackOpt = slotReference.resolve(player);
        if (stackOpt.isEmpty()) {
            return;
        }
        // 仿 RS 原版：不检查网络绑定与电量，总是打开界面；未绑定/没电时界面内操作禁用（由 RS 原版机制处理）。
        rescueLegacyExtraSlot(player, stackOpt.get());
        openModeScreen(player, stackOpt.get(), slotReference);
    }

    @Override
    protected void use(@Nullable final Component name,
                       final ServerPlayer player,
                       final SlotReference slotReference,
                       final NetworkItemContext context) {
        useFromShortcut(player, slotReference);
    }

    /**
     * 旧数据抢救（一次性）：早期版本给无线终端加过「额外槽位」，内容存在终端物品自身的
     * {@code CustomData} 键 {@link #TAG_LEGACY_EXTRA_SLOT} 里。该实现已删除（改由 Curios 饰品槽承载），
     * 这里在终端被使用时把残留的那一件物品<b>原样还给玩家</b>（背包满则掉在脚边）并删掉该键，
     * 保证玩家此前放进去的东西不会凭空消失。
     */
    private static void rescueLegacyExtraSlot(final ServerPlayer player, final ItemStack stack) {
        final var customData = stack.getOrDefault(
            net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY).getUnsafe();
        if (!customData.contains(TAG_LEGACY_EXTRA_SLOT, net.minecraft.nbt.CompoundTag.TAG_COMPOUND)) {
            return;
        }
        final ItemStack legacy = ItemStack.parseOptional(player.level().registryAccess(),
            customData.getCompound(TAG_LEGACY_EXTRA_SLOT));
        stack.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY,
            data -> data.update(tag -> tag.remove(TAG_LEGACY_EXTRA_SLOT)));
        if (legacy.isEmpty()) {
            return;
        }
        if (!player.getInventory().add(legacy)) {
            player.drop(legacy, false);
        }
        player.getInventory().setChanged();
        LOGGER.info("Rescued legacy terminal extra-slot content: {}", legacy.getItem());
    }

    /**
     * 该模式是否有对应的 RS 原版无线界面可复用。
     * 样板终端 / 合成仓管理器通过"虚拟方块实体"在服务端构造 RS 原版菜单（客户端仍走 data 版重建）；
     * 序列装配样板终端为本模组自建界面（虚拟方块实体 + 自建菜单）。
     */
    public static boolean isModeSupported(final int mode) {
        return mode == MODE_GRID || mode == MODE_MONITOR || mode == MODE_PATTERNS
            || mode == MODE_MANAGER || mode == MODE_SEQUENCE || mode == MODE_UNIT_MANAGER;
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
                case MODE_SEQUENCE -> openSequencePatternTerminal(player, stack, slotReference, title);
                case MODE_UNIT_MANAGER -> openUnitPatternManager(player, stack, slotReference, title);
                default -> openWirelessCraftingGrid(player, stack, slotReference);
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
    /** RS 官方配件 Quartz Arsenal：其无线合成终端才是"合成终端"模式的原版实现。 */
    private static final String RS_QUARTZ_ARSENAL_ITEMS =
        "com.refinedmods.refinedstorage.quartzarsenal.common.Items";

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
            com.refinedmods.refinedstorage.common.content.Blocks.INSTANCE.getPatternGrid().getDefault().defaultBlockState());
        // 设置虚拟 BE 的 level（配方矩阵/切石机需要 getLevel() 非 null）
        setVirtualBeLevel(be, player.level());

        // 绑定终端解析出的 RS 网络到虚拟 BE 的节点，使其 Grid 方法可访问网络存储。
        // 未绑定 / 网络不可达 / 绑定失败时绑定"空网络"，界面照常打开显示灰色空状态（仿原版无线网格"未连接"）。
        if (!bindNetworkSafely(be, stack, player, slotReference)) {
            bindEmptyNetworkToVirtualBe(be);
        }

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
            com.refinedmods.refinedstorage.common.content.Blocks.INSTANCE.getAutocrafterManager().getDefault().defaultBlockState());
        setVirtualBeLevel(be, player.level());

        // 绑定网络（管理器按网络中的自动合成仓分组显示）；未绑定时绑定空网络打开灰色界面
        if (!bindNetworkSafely(be, stack, player, slotReference)) {
            bindEmptyNetworkToVirtualBe(be);
        }

        final Class<?> menuClass = Class.forName(RS_MANAGER_MENU);
        // AutocrafterManagerContainerMenu(int, Inventory, AutocrafterManagerBlockEntity, List<Group>)
        final java.lang.reflect.Constructor<?> menuCtor = menuClass.getDeclaredConstructor(
            int.class, net.minecraft.world.entity.player.Inventory.class, beClass, java.util.List.class);
        menuCtor.setAccessible(true);

        final Class<?> dataClass = Class.forName(
            "com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager.AutocrafterManagerData");
        final java.lang.reflect.Field streamCodecField = dataClass.getField("STREAM_CODEC");
        final Object streamCodec = streamCodecField.get(null);

        // 管理器菜单构造器必须传「服务端真实 groups」：原版 createMenu 调用 BE 私有 getGroups()，
        // 它会把网络中每个自动合成仓的 PatternContainer 真实槽位绑进菜单。
        // 若传空列表，服务端只剩玩家背包槽，与客户端（按 data 的组槽布局）错位，
        // 导致服务端把背包内容同步到"管理器槽位"（显示升级件）、点击/shift 把物品塞进真实自动合成仓、
        // 原样板被覆盖 —— 即"切到合成仓管理后物品被复制/错乱"的恶性 bug。
        // 虚拟 BE 的网络已在上面 bindNetworkSafely 时写入，getGroups() 依赖 mainNetworkNode.getNetwork()。
        final Object serverGroups = invokeGetGroups(be);
        final java.lang.Object[] extraArgs = {serverGroups};
        openReflectedMenu(player, be, dataClass, streamCodec, title, menuCtor, extraArgs);
    }

    /**
     * MODE 4：序列装配样板终端（虚拟方块实体 + 自建菜单，纯样板编辑器，无需网络）。
     * <h2>为什么必须读写<b>物品 NBT</b></h2>
     * <p>该模式由终端<b>物品</b>打开：世界中没有对应的方块实体，虚拟方块实体只是内存里的临时对象。
     * 若只 new 一个空的方块实体，玩家放进<b>终端自己槽位</b>的东西（3 格样板输入槽 / 总样板槽 /
     * 单元样板库 / 老存档编排容器）会在关闭界面时随对象一起消失 —— 那是<b>销毁物品</b>
     * （用户硬性要求「严禁复制 / 销毁物品」）。因此这里把方块实体整体序列化进物品的
     * {@code CustomData}（键 {@link #TAG_SEQUENCE_TERMINAL}）：开界面时读回、关界面时写回，
     * 内容跟着终端物品走，与「把终端方块放下来用」完全等价。</p>
     * <p>写回时机由菜单的关闭钩子（{@code setTerminalCloseSaver}）触发，只在服务端执行一次。</p>
     */
    private void openSequencePatternTerminal(final ServerPlayer player,
                                             final ItemStack stack,
                                             final SlotReference slotReference,
                                             final Component title) {
        final cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity be =
            new cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity(
                net.minecraft.core.BlockPos.ZERO,
                cretae.cookiewyq.rs_create_compat.RS_Create_Compat.SEQUENCE_PATTERN_TERMINAL_BLOCK.get()
                    .defaultBlockState());
        be.setLevel(player.level());
        // 读回物品 NBT 里的终端内容（老物品没有该键 = 全新空终端，原样打开，不报错）
        final net.minecraft.nbt.CompoundTag saved = stack.getOrDefault(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY)
            .getUnsafe().getCompound(TAG_SEQUENCE_TERMINAL);
        if (!saved.isEmpty()) {
            try {
                be.loadAdditional(saved, player.level().registryAccess());
            } catch (final RuntimeException e) {
                LOGGER.warn("Failed to load sequence terminal contents from item, opening empty", e);
            }
        }
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
            (id, inv, p) -> {
                final cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu menu =
                    cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu.create(id, inv, be);
                menu.setTerminalCloseSaver(() -> saveSequenceTerminal(player, slotReference, stack, be));
                return menu;
            },
            title));
    }

    /**
     * 把虚拟序列装配样板终端的内容写回<b>终端物品</b>的 NBT（关闭界面时唯一一次写入）。
     * <p>优先按 {@link SlotReference} 现取存活栈（物品被移动过也能写对那一份）；取不到时退回打开界面时
     * 捕获的那一份。取不到任何一份（物品已被销毁）时什么都不做 —— 内容随被销毁的物品一起消失，
     * 与「破坏终端方块掉落内容」不冲突。</p>
     */
    private static void saveSequenceTerminal(final ServerPlayer player,
                                             final SlotReference slotReference,
                                             final ItemStack fallback,
                                             final cretae.cookiewyq.rs_create_compat.block.entity
                                                 .SequencePatternTerminalBlockEntity be) {
        final ItemStack target = slotReference.resolve(player).orElse(fallback);
        if (target.isEmpty()) {
            return;
        }
        final net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        be.saveAdditional(tag, player.level().registryAccess());
        target.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY,
            data -> data.update(t -> t.put(TAG_SEQUENCE_TERMINAL, tag)));
    }

    /**
     * MODE 5：单元样板管理舱（虚拟方块实体 + 本模组自建菜单）。
     * <p>与 MODE 2（合成仓管理）同一套「虚拟方块实体」做法：把终端绑定的 RS 网络写进虚拟方块实体，
     * 界面即可列出该网络内各执行舱的单元样板；未绑定 / 网络不可达时绑定空网络，
     * 界面照常打开并显示灰色的空状态（不出错、不崩）。</p>
     * <p>此前那格「额外槽位」（把内容存在终端物品数据组件里）已删除：随终端携带的槽位改由
     * <b>Curios 饰品槽</b>承载（由<b>前置模组</b> {@code refinedstorage_curios_integration} 提供，
     * 本模组不再自建槽位；终端经物品标签登记进前置槽位）。</p>
     */
    private void openUnitPatternManager(final ServerPlayer player,
                                        final ItemStack stack,
                                        final SlotReference slotReference,
                                        final Component title) {
        final cretae.cookiewyq.rs_create_compat.block.entity.UnitPatternManagerBlockEntity be =
            new cretae.cookiewyq.rs_create_compat.block.entity.UnitPatternManagerBlockEntity(
                net.minecraft.core.BlockPos.ZERO,
                cretae.cookiewyq.rs_create_compat.RS_Create_Compat.UNIT_PATTERN_MANAGER_BLOCK.get()
                    .defaultBlockState());
        setVirtualBeLevel(be, player.level());
        if (!bindNetworkSafely(be, stack, player, slotReference)) {
            bindEmptyNetworkToVirtualBe(be);
        }
        cretae.cookiewyq.rs_create_compat.menu.UnitPatternManagerMenu.open(player, be, title);
    }

    /** 合成终端模式：调用 RS 官方配件 Quartz Arsenal 的<b>原版</b>无线合成终端物品（Universal-Grid 同款调用）。
     *  <p>通过反射避免编译期硬依赖；若配件未安装则回退 RS 原版无线终端，保证界面仍可打开。 */
    private static void openWirelessCraftingGrid(final ServerPlayer player,
                                                 final ItemStack stack,
                                                 final SlotReference slotReference) {
        try {
            final Class<?> itemsClass = Class.forName(RS_QUARTZ_ARSENAL_ITEMS);
            final Object items = itemsClass.getField("INSTANCE").get(null);
            final Object craftingGridItem = itemsClass.getMethod("getWirelessCraftingGrid").invoke(items);
            final java.lang.reflect.Method use = craftingGridItem.getClass().getMethod(
                "use", ServerPlayer.class, ItemStack.class, SlotReference.class);
            use.invoke(craftingGridItem, player, stack, slotReference);
        } catch (final Throwable t) {
            LOGGER.warn("Quartz Arsenal not present / invocation failed; falling back to vanilla wireless grid", t);
            Items.INSTANCE.getWirelessGrid().use(player, stack, slotReference);
        }
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

    /** 反射调用 AutocrafterManagerBlockEntity 私有 getGroups()，取网络中真实自动合成仓分组。
     *  返回类型为 List<AutocrafterManagerBlockEntity.Group>，菜单构造器按 Object 接收即可。 */
    private static Object invokeGetGroups(final Object be) {
        try {
            final java.lang.reflect.Method getGroups = be.getClass().getDeclaredMethod("getGroups");
            getGroups.setAccessible(true);
            final Object groups = getGroups.invoke(be);
            return groups == null ? java.util.List.of() : groups;
        } catch (final ReflectiveOperationException e) {
            LOGGER.warn("Failed to reflect AutocrafterManager getGroups(), falling back to empty groups", e);
            return java.util.List.of();
        }
    }

    private static boolean bindNetworkSafely(final Object be,
                                             final ItemStack stack,
                                             final ServerPlayer player,
                                             final SlotReference slotReference) {
        try {
            return bindNetworkToVirtualBe(be, stack, player, slotReference);
        } catch (final ReflectiveOperationException e) {
            LOGGER.warn("Failed to bind terminal network to virtual block entity, falling back to empty network", e);
            return false;
        }
    }

    /**
     * 将终端绑定到的 RS 网络绑定到虚拟方块实体的 mainNetworkNode（反射访问 protected 字段）。
     * <p>
     * 与 {@code NetworkItemContext.resolveNetwork()} 不同：这里<b>跳过无线发射器范围检查</b>，
     * 只要终端已绑定到任意网络节点方块且其网络存在即可打开（样板/管理器是方块界面，不需要远程范围）。
     *
     * @return 是否成功绑定网络（未绑定 / 绑定位置失效时返回 false，避免构造菜单时 NPE）
     */
    private static boolean bindNetworkToVirtualBe(final Object be,
                                                  final ItemStack stack,
                                                  final ServerPlayer player,
                                                  final SlotReference slotReference) throws ReflectiveOperationException {
        final com.refinedmods.refinedstorage.common.content.DataComponents dc =
            com.refinedmods.refinedstorage.common.content.DataComponents.INSTANCE;
        final net.minecraft.core.GlobalPos location = stack.get(dc.getNetworkLocation());
        if (location == null) {
            return false; // 未绑定网络：提示玩家绑定
        }
        final var server = player.getServer();
        if (server == null) {
            return false;
        }
        final net.minecraft.server.level.ServerLevel boundLevel = server.getLevel(location.dimension());
        if (boundLevel == null || !boundLevel.isLoaded(location.pos())) {
            return false; // 绑定位置未加载
        }
        final net.minecraft.world.level.block.entity.BlockEntity boundBe = boundLevel.getBlockEntity(location.pos());
        if (!(boundBe instanceof com.refinedmods.refinedstorage.common.api.support.network.item.NetworkItemTargetBlockEntity target)) {
            return false; // 绑定位置不是网络节点方块
        }
        final com.refinedmods.refinedstorage.api.network.Network network = target.getNetworkForItem();
        if (network == null) {
            return false; // 绑定节点尚未接入网络
        }
        setVirtualBeNetwork(be, network, true);
        return true;
    }

    /**
     * 反射把网络写入虚拟方块实体的 mainNetworkNode（双保险：写 network 字段 + 调 setNetwork 方法），
     * 并标记节点激活。若最终 getNetwork() 仍为 null 则抛出异常（便于日志定位）。
     */
    private static void setVirtualBeNetwork(final Object be,
                                            final com.refinedmods.refinedstorage.api.network.Network network,
                                            final boolean active) throws ReflectiveOperationException {
        final Class<?> beClass = be.getClass();
        java.lang.reflect.Field nodeField = null;
        Class<?> c = beClass;
        while (c != null && nodeField == null) {
            try {
                nodeField = c.getDeclaredField("mainNetworkNode");
            } catch (final NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        if (nodeField == null) {
            throw new NoSuchFieldException("mainNetworkNode not found in " + beClass.getName());
        }
        nodeField.setAccessible(true);
        final Object node = nodeField.get(be);
        if (node == null) {
            throw new IllegalStateException("mainNetworkNode is null in " + beClass.getName());
        }
        // 1) 优先写字段（最可靠，不受方法可见性影响）
        final java.lang.reflect.Field networkField = findField(node.getClass(), "network");
        if (networkField != null) {
            networkField.setAccessible(true);
            networkField.set(node, network);
        }
        // 2) 再调 setNetwork 方法（触发 watchers 挂接等副作用）
        try {
            final java.lang.reflect.Method setNetwork = node.getClass().getMethod("setNetwork",
                com.refinedmods.refinedstorage.api.network.Network.class);
            setNetwork.setAccessible(true);
            setNetwork.invoke(node, network);
        } catch (final NoSuchMethodException ignored) {
            // 无 public setNetwork：字段写入已足够
        }
        // 3) 标记激活
        try {
            final java.lang.reflect.Method setActive = node.getClass().getMethod("setActive", boolean.class);
            setActive.setAccessible(true);
            setActive.invoke(node, active);
        } catch (final NoSuchMethodException ignored) {
            final java.lang.reflect.Field activeField = findField(node.getClass(), "active");
            if (activeField != null) {
                activeField.setAccessible(true);
                activeField.set(node, active);
            }
        }
        // 4) 验证写入成功
        final java.lang.reflect.Method getNetwork = node.getClass().getMethod("getNetwork");
        final Object actual = getNetwork.invoke(node);
        if (actual != network) {
            LOGGER.error("Virtual BE network binding failed: expected {}, actual {}", network, actual);
            throw new IllegalStateException("Virtual BE network binding failed");
        }
    }

    private static java.lang.reflect.Field findField(Class<?> type, final String name) {
        while (type != null) {
            try {
                return type.getDeclaredField(name);
            } catch (final NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    /** 将空网络绑定到虚拟方块实体（未绑定 / 网络不可达时，界面打开但显示灰色空状态）。 */
    private static void bindEmptyNetworkToVirtualBe(final Object be) {
        try {
            setVirtualBeNetwork(be, cretae.cookiewyq.rs_create_compat.network.EmptyNetwork.INSTANCE, true);
        } catch (final ReflectiveOperationException e) {
            LOGGER.warn("Failed to bind empty network to virtual block entity", e);
        }
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
            public @NotNull Component getDisplayName() {
                return title;
            }

            @Override
            public @NotNull AbstractContainerMenu createMenu(final int syncId,
                                                             final net.minecraft.world.entity.player.Inventory inv,
                                                             final net.minecraft.world.entity.player.Player p) {
                try {
                    // 构造器签名：(int syncId, Inventory inv, BlockEntity be[, extra...])
                    final java.lang.Object[] args = new java.lang.Object[3 + extraMenuArgs.length];
                    args[0] = syncId;
                    args[1] = inv;
                    args[2] = be;
                    System.arraycopy(extraMenuArgs, 0, args, 3, extraMenuArgs.length);
                    return (AbstractContainerMenu) menuCtor.newInstance(args);
                } catch (final ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        Platform.INSTANCE.getMenuOpener().openMenu(player, provider);
    }
}


