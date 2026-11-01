package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.FEEnergyCubeBlockEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * FE 能量立方菜单：<b>没有任何物品槽</b>（立方没有库存），也不显示玩家背包
 * （与 {@code RangeChargerMenu} 同类：无槽界面只画自己的面板）。
 *
 * <p>界面上的全部数值来自 {@link FEEnergyCubeBlockEntity#getContainerData()} 的 21 个数据槽，
 * 服务端权威、客户端只读。客户端菜单由注册期的二参工厂重建，{@code cube == null} ——
 * 因此 <b>界面绝不允许</b>通过本类的 {@link #getCube()} 读方块实体（那是给服务端包处理体用的）。
 * 门控状态（数据槽 7）与网络电量趋势（数据槽 17）都在服务端算好，客户端不重算 ——
 * 两侧各算一遍必然分叉，趋势尤其如此（采样相位不同，客户端算出来的「涨/没涨」会和门控不一致）。
 */
public class FEEnergyCubeMenu extends AbstractContainerMenu {
    /** 数据槽数量（必须与方块实体一致，否则 addDataSlots 之后的同步会越界 / 少一格）。 */
    public static final int DATA_SLOT_COUNT = FEEnergyCubeBlockEntity.DATA_SLOT_COUNT;

    // 门控四态编码（与方块实体同一组常量，界面只做比较、不猜语义）
    public static final int GATE_NO_NETWORK = FEEnergyCubeBlockEntity.GATE_NO_NETWORK;
    public static final int GATE_ALLOW = FEEnergyCubeBlockEntity.GATE_ALLOW;
    /** 控制器（非立方池）低于阈值：<b>外部 FE 也被拦</b>。 */
    public static final int GATE_DENY_LOW_CONTROLLER = FEEnergyCubeBlockEntity.GATE_DENY_LOW_CONTROLLER;
    /** 阈值达标但网络电量没在涨：只拦「从网络抽电」，外部 FE 照收。 */
    public static final int GATE_DENY_NOT_RISING = FEEnergyCubeBlockEntity.GATE_DENY_NOT_RISING;

    // 网络电量趋势三态编码（数据槽 17，与方块实体同一组常量）
    public static final int TREND_UNKNOWN = FEEnergyCubeBlockEntity.TREND_UNKNOWN;
    public static final int TREND_RISING = FEEnergyCubeBlockEntity.TREND_RISING;
    public static final int TREND_FLAT = FEEnergyCubeBlockEntity.TREND_FLAT;
    public static final int TREND_FALLING = FEEnergyCubeBlockEntity.TREND_FALLING;

    /** 趋势采样窗口（tick，纯代码常量，客户端与服务端一致；界面用它写「最近 N tick」）。 */
    public static final int TREND_WINDOW_TICKS = FEEnergyCubeBlockEntity.TREND_WINDOW_TICKS;

    // 相邻供能三态编码（数据槽 20，与方块实体同一组常量；第 106 轮）
    /** 六个相邻面没有任何可接收 FE 的方块（相邻只有能量立方 / 空气 / 不放行的机器）。 */
    public static final int ADJACENT_SUPPLY_NONE = FEEnergyCubeBlockEntity.ADJACENT_SUPPLY_NONE;
    /** 有可接收的相邻方块，且本 tick 真的送出了电（「开」）。 */
    public static final int ADJACENT_SUPPLY_ON = FEEnergyCubeBlockEntity.ADJACENT_SUPPLY_ON;
    /** 有可接收的相邻方块，但本 tick 没送出去（本块没电 / 对方都满了，「关」）。 */
    public static final int ADJACENT_SUPPLY_OFF = FEEnergyCubeBlockEntity.ADJACENT_SUPPLY_OFF;

    private final FEEnergyCubeBlockEntity cube;
    private final ContainerData data;

    /** 客户端重建菜单用（无方块实体，数据由数据槽同步）。 */
    public FEEnergyCubeMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public FEEnergyCubeMenu(final int id, final Inventory inventory, @Nullable final FEEnergyCubeBlockEntity cube) {
        super(RS_Create_Compat.FE_ENERGY_CUBE_MENU.get(), id);
        this.cube = cube;
        this.data = cube != null ? cube.getContainerData() : new SimpleContainerData(DATA_SLOT_COUNT);
        addDataSlots(data);
        // 故意不 addSlot：立方没有库存、没有升级槽，玩家背包也不显示。
    }

    public static FEEnergyCubeMenu create(final int id, final Inventory inventory,
                                          final FEEnergyCubeBlockEntity cube) {
        return new FEEnergyCubeMenu(id, inventory, cube);
    }

    // ==================== 数据槽读取（客户端界面唯一数据来源） ====================

    /** 本块存量（FE）。 */
    public int getOwnEnergy() {
        return data.get(0);
    }

    /** 本块容量（FE，= Config.feEnergyCubeCapacity）。 */
    public int getOwnCapacity() {
        return data.get(1);
    }

    /** 集群存量（FE；高 32 位 / 低 32 位由方块实体的 lo/hi 配对给出）。 */
    public long getClusterEnergy() {
        return FEEnergyCubeBlockEntity.join(data.get(2), data.get(3));
    }

    /** 集群容量（FE，= 块数 × 单块容量）。 */
    public long getClusterCapacity() {
        return FEEnergyCubeBlockEntity.join(data.get(4), data.get(5));
    }

    /** 集群块数（相邻六面接触的立方数量，含自己）。 */
    public int getMemberCount() {
        return data.get(6);
    }

    /** 门控四态：{@link #GATE_NO_NETWORK} / {@link #GATE_ALLOW} / {@link #GATE_DENY_LOW_CONTROLLER} /
     *  {@link #GATE_DENY_NOT_RISING}。 */
    public int getGateState() {
        return data.get(7);
    }

    /**
     * 网络电量趋势（数据槽 17）：{@link #TREND_UNKNOWN} 采样中 / {@link #TREND_RISING} 上涨 /
     * {@link #TREND_FLAT} 持平 / {@link #TREND_FALLING} 下降。
     * <p>服务端按 {@link #TREND_WINDOW_TICKS} tick 采样得出，客户端只读 —— 界面<b>绝不</b>自己比较
     * 两次读数（两侧的采样相位不同，各算一遍必然分叉）。
     */
    public int getTrendState() {
        return data.get(17);
    }

    /** 上个采样窗口的网络能量池净变化（FE，带符号；只用于展示 / 排错，不参与门控）。 */
    public long getTrendDelta() {
        return FEEnergyCubeBlockEntity.join(data.get(18), data.get(19));
    }

    /**
     * 相邻供能三态（数据槽 20，第 106 轮）：
     * {@link #ADJACENT_SUPPLY_NONE} 无相邻 / {@link #ADJACENT_SUPPLY_ON} 开 / {@link #ADJACENT_SUPPLY_OFF} 关。
     * <p><b>语义（服务端每 tick 实测，客户端只读）</b>：有可接收 FE 的相邻方块且本 tick 真送出了电 ⇒ 开；
     * 有对象但本 tick 一个 FE 都没送出去（本块没电 / 对方满了）⇒ 关；一个可接收的邻居都没有
     * （含「相邻的只有能量立方」）⇒ 无相邻。
     */
    public int getAdjacentSupplyState() {
        return data.get(20);
    }

    /** 计费模式：{@code true} = 绝对 FE，{@code false} = 百分比。 */
    public boolean isAbsoluteMode() {
        return data.get(8) == 1;
    }

    public int getThresholdPercent() {
        return data.get(9);
    }

    public int getThresholdAbsolute() {
        return data.get(10);
    }

    /**
     * 「非立方」存量：网络池总量 − <b>本网络所有</b>能量立方 = 控制器那一部分（等效口径，
     * 见方块实体类注释的前提说明）。界面状态行的百分比由它算。
     */
    public long getNonCubeStored() {
        return FEEnergyCubeBlockEntity.join(data.get(11), data.get(12));
    }

    /** 「非立方」容量（同上）。 */
    public long getNonCubeCapacity() {
        return FEEnergyCubeBlockEntity.join(data.get(13), data.get(14));
    }

    /**
     * 对外充放速率上限（FE/t，服务端下发）。
     * <p><b>为什么走数据槽而不是界面读 {@code Config}</b>：专服上客户端配置可能和服务端不同，
     * 界面读本地 {@code Config} 会显示错的速率（仓库里范围充电器已经踩过同类问题，
     * 见 {@code RangeChargerMenu#getChargeRate()} 的注释）。速率永远是「服务端实际在用的值」。
     */
    public int getMaxTransfer() {
        return data.get(15);
    }

    /** 网络抽取速率（FE/t，服务端下发）。 */
    public int getNetworkPullPerTick() {
        return data.get(16);
    }

    /** 服务端菜单持有的方块实体；<b>客户端恒为 null</b>（只给服务端包处理体 / stillValid 用）。 */
    @Nullable
    public FEEnergyCubeBlockEntity getCube() {
        return cube;
    }

    @Override
    public boolean stillValid(final Player player) {
        return cube != null
            && cube.getLevel() != null
            && cube.getLevel().getBlockEntity(cube.getBlockPos()) == cube
            && player.distanceToSqr(cube.getBlockPos().getX() + 0.5,
            cube.getBlockPos().getY() + 0.5,
            cube.getBlockPos().getZ() + 0.5) <= 64.0;
    }

    /** 没有物品槽：快速移动永远为空操作。 */
    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        return ItemStack.EMPTY;
    }
}
