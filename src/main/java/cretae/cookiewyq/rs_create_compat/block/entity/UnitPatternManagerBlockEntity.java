package cretae.cookiewyq.rs_create_compat.block.entity;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.impl.node.SimpleNetworkNode;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.support.network.AbstractBaseNetworkNodeContainerBlockEntity;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.network.UnitPatternManagerData;
import cretae.cookiewyq.rs_create_compat.support.UnitManagerSources;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 「单元样板管理舱」方块实体：接入 RS 网络后，把网络内<b>各台执行舱的单元样板</b>
 * 按执行舱分组呈现并允许直接管理（查看 / 放入 / 取出 / 移动）。
 *
 * <p><b>只展示单元样板</b>：序列装配样板库里的<b>总样板</b>不在这里出现 ——
 * 总样板由 RS 原版「自动合成管理舱」管理（本模组的样板库对它注册为类自动合成仓），
 * 本方块只管单元样板，两者职责不重叠。</p>
 *
 * <p><b>总样板怎么被挡在界面外（服务端定下标、两端照做）</b>：{@link #openEntries()} 暴露的是各容器的<b>原始内容</b>
 * （客户端槽位同步要求两端下标一一对应，这里不能抽掉任何一格），而读档路径不做物品类型校验
 * （执行舱 {@code UnitSlots} / 终端 {@code UnitLibrary} 都是原样读回），因此旧存档里可能真的躺着一张总样板。
 * 于是 {@link #getMenuData(List)} 用 {@link UnitManagerSources#hiddenMasterPatternSlots} 按<b>物品类型</b>
 * 扫出这些格的下标，随开界面一起下发；两端只把这几格<b>挪出可见网格</b>（<b>不</b>删数据、<b>不</b>改下标）。</p>
 *
 * <p><b>为什么必须由服务端算这份下标（上一版的教训）</b>：客户端的镜像容器要等原版槽位同步包才有内容，
 * 而它是在菜单构造 / 界面 {@code init} 那一刻就按「本组多少格」重建槽位的 —— 那一刻容器还是空的，
 * 所以「客户端自己按内容隐藏」在首帧必然全部漏判（旧存档里的总样板照常显示）。
 * 服务端在开界面时扫真实容器并下发下标，客户端一开始就知道藏哪几格，问题从根上不再依赖时序。</p>
 *
 * <p><b>与 RS「自动合成管理舱」（Autocrafter Manager）的分工</b>：后者管理自动合成样板
 * （RS 的自动合成仓 / 本模组的序列装配样板库），本方块只管<b>单元样板</b>，
 * 界面布局照抄 RS 的自动合成管理器（分组标题 + 9 列槽位 + 拉伸滚动），
 * 分组维度同样是「<b>连在一起的一串机器 = 一组</b>」—— 执行舱成链（或贴成相邻集群）后合并显示成
 * 一个条目（标题 = 代表名 + 台数，槽位 = 各成员单元槽的合并视图，写入落回各自的舱），
 * 而<b>名字 / 配方类型没配好</b>的执行舱不显示（还没接进任何产线，显示出来只会是空组）。</p>
 *
 * <p><b>界面结构为什么要发给客户端</b>：槽位数取决于网络里有多少台执行舱，
 * 客户端无法自行推导，而原版槽位同步要求两端槽位下标顺序一致；
 * 因此 {@link #openEntries()} + {@link #getMenuData(List)}
 * 会把分组结构随开界面一起下发。</p>
 *
 * <p><b>额外槽位的历史遗留</b>：早期版本本方块自带 1 格「额外槽位」（经无线终端打开时内容存在终端物品上）。
 * 该槽位已从界面与菜单中<b>整体移除</b> —— 随终端携带的槽位改由 <b>Curios 饰品槽</b>承载
 * （由<b>前置模组</b> {@code refinedstorage_curios_integration} 提供，本模组不再自建槽位）。这里保留的
 * {@link #extraSlot} 仅为<b>旧存档抢救</b>：登录旧存档后它的内容仍会随 NBT 读写，破坏方块时原样掉落，
 * 绝不销毁玩家物品。
 */
public class UnitPatternManagerBlockEntity
    extends AbstractBaseNetworkNodeContainerBlockEntity<SimpleNetworkNode> {
    /** 持久化键：历史遗留的「额外槽位」（界面已移除，仅用于旧存档抢救与掉落）。 */
    private static final String TAG_EXTRA_SLOT = "ExtraSlot";
    /** 「序列装配样板终端旧单元样板库」分组的显示名语言键（旧数据抢救出口）。 */
    public static final String TERMINAL_GROUP_KEY = "gui.rs_create_compat.unit_pattern_manager.group.terminal";

    /** 额外槽位（1 格，可放任意物品）。界面已移除该槽位，此容器仅作旧存档抢救 + 破坏方块时掉落。 */
    private final SimpleContainer extraSlot = new SimpleContainer(1);

    public UnitPatternManagerBlockEntity(final BlockPos pos, final BlockState state) {
        super(RS_Create_Compat.UNIT_PATTERN_MANAGER_BLOCK_ENTITY.get(), pos, state,
            new SimpleNetworkNode(Config.unitPatternManagerEnergyUsage));
    }

    /** 额外槽位容器（仅旧存档抢救 + 破坏方块时原样掉落用，界面不再暴露）。 */
    public SimpleContainer extraSlot() {
        return extraSlot;
    }

    /** 本节点是否处于「已接入且已通电」状态（未接入时界面显示为灰色的空状态，与 RS 管理器一致）。 */
    public boolean isNodeActive() {
        return mainNetworkNode.isActive();
    }

    @Nullable
    public Network networkOrNull() {
        return mainNetworkNode.getNetwork();
    }

    // ==================== 分组数据 ====================

    /**
     * 打开界面所需的「一次打开」模型：每个分组 = 标题 + 一份容器 + 是否只出不进。
     *
     * @param nameIsKey 标题是翻译键（由客户端翻译，避免服务端把翻译键当裸串显示）
     * @param recipeType 本组的配方类型 id（客户端据此显示<b>配方名称</b>并在提示里附原始 id）
     * @param container 本组的容器（与服务端槽位用的是<b>同一个实例</b>，
     *                  因此客户端看到的槽位数与服务端严格一致）
     * @param extractOnly 该组是否只出不进（可取出、不可放入）
     */
    public record Entry(String name, boolean nameIsKey, String recipeType,
                        net.minecraft.world.Container container, boolean extractOnly) {
    }

    /** 构建本次打开界面的分组模型（服务端权威；顺序 = 各分组按最小坐标升序 → 各终端旧库）。 */
    public List<Entry> openEntries() {
        final List<Entry> entries = new ArrayList<>();
        // 「成链 / 贴成一排的执行舱」合并成一个分组（照 RS 原版自动合成管理器的合并显示）；
        // 「名字或配方类型没配好」的执行舱由 chamberGroups 直接滤掉（用户要求：不显示）。
        for (final UnitManagerSources.ChamberGroup group
            : UnitManagerSources.chamberGroups(networkOrNull())) {
            entries.add(new Entry(group.name(), false, group.recipeType(),
                group.container(), false));
        }
        // 序列装配样板终端自带的「单元样板库」：自 v7 起终端界面不再承载它（那块 UI 已删除），
        // 这里作为<b>只出不进</b>的分组暴露出来，让旧存档里的样板能被取出并搬进执行舱（旧数据不丢）。
        for (final SequencePatternTerminalBlockEntity terminal
            : UnitManagerSources.terminals(networkOrNull())) {
            entries.add(new Entry(TERMINAL_GROUP_KEY, true, "",
                new UnitManagerSources.TerminalLibraryContainer(terminal), true));
        }
        return entries;
    }

    /** 由同一份 {@link Entry} 模型导出下发给客户端的界面结构数据。 */
    public UnitPatternManagerData getMenuData(final List<Entry> entries) {
        final List<UnitPatternManagerData.Section> sections = new ArrayList<>(entries.size());
        for (final Entry entry : entries) {
            sections.add(new UnitPatternManagerData.Section(
                entry.name(), entry.nameIsKey(), entry.recipeType(),
                entry.container().getContainerSize(), entry.extractOnly(),
                // 总样板所在格：服务端现在扫一遍真实容器，把下标随界面一起下发（客户端首帧就能藏起来）
                UnitManagerSources.hiddenMasterPatternSlots(entry.container())));
        }
        // 「生成时自动跳过重复样板」的档位不再随界面下发（管理舱的全局开关已按用户要求删除；
        // 每步开关属于序列装配终端，整条链路不再经过本界面）。
        return new UnitPatternManagerData(sections, isNodeActive());
    }

    // ==================== 基础 ====================

    @Override
    public Component getName() {
        return getBlockState().getBlock().getName();
    }

    /** 本机没有红石模式（与序列装配样板库一致）。 */
    @Override
    protected boolean hasRedstoneMode() {
        return false;
    }

    /** 注册方块能力：RS 网络节点容器。 */
    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            RS_Create_Compat.UNIT_PATTERN_MANAGER_BLOCK_ENTITY.get(),
            (blockEntity, direction) -> blockEntity.getContainerProvider()
        );
    }

    @Override
    public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put(TAG_EXTRA_SLOT, extraSlot.createTag(registries));
    }

    @Override
    public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(TAG_EXTRA_SLOT)) {
            extraSlot.fromTag(tag.getList(TAG_EXTRA_SLOT, CompoundTag.TAG_COMPOUND), registries);
        }
    }
}
