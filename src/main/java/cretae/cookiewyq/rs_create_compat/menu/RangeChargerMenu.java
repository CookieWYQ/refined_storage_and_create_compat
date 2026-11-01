package cretae.cookiewyq.rs_create_compat.menu;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.RangeChargerBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.RsccRateLimiter;
import cretae.cookiewyq.rs_create_compat.support.RsccRedstoneMode;
import cretae.cookiewyq.rs_create_compat.support.RsccRedstoneModeHolder;
import com.refinedmods.refinedstorage.common.support.RedstoneMode;
import com.refinedmods.refinedstorage.common.support.RedstoneModeSettings;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 范围充电器菜单：无物品槽（不显示玩家背包），通过 ContainerData 同步范围与能量，
 * 按钮消息走原版 clickMenuButton。
 */
public class RangeChargerMenu extends AbstractContainerMenu implements RsccRedstoneModeHolder {
    /** 按钮 id：0/1 = X 减/增，2/3 = Y 减/增，4/5 = Z 减/增，6 = 「查看充电范围」（C2S 请求，服务端校验后回 S2C 快照）。 */
    public static final int BUTTON_SHOW_RANGE = 6;
    /**
     * 7 = 「高亮充电目标」开关（每台充电器一个，落盘到方块实体）。
     * <p>用户要求「单独搞一个按钮出来」：配置项 {@code rangeChargerHighlightTargets} 只是总闸，
     * 每台机器还需要自己的开关，否则「这台看腻了」只能改配置文件（影响全服所有充电器）。</p>
     */
    public static final int BUTTON_TOGGLE_HIGHLIGHT = 7;
    private final RangeChargerBlockEntity charger;
    private final ContainerData data;

    /** 客户端重建菜单用（无方块实体，数据由数据槽同步）。 */
    public RangeChargerMenu(final int id, final Inventory inventory) {
        this(id, inventory, null);
    }

    public RangeChargerMenu(final int id, final Inventory inventory, @Nullable final RangeChargerBlockEntity charger) {
        super(RS_Create_Compat.RANGE_CHARGER_MENU.get(), id);
        this.charger = charger;
        // 14 个数据槽：0..13（12 = 本机高亮开关，客户端按钮据此画开关态；
        // 13 = 上一次扫描因「已经充满」被跳过的目标数，第 102 轮新增）。
        // 客户端必须与服务端的槽数一致，否则 addDataSlots 之后的同步会越界 / 少一格。
        this.data = charger != null ? charger.getContainerData() : new SimpleContainerData(14);
        addDataSlots(data);

        // 插件槽（6 格竖排，界面右侧独立栏，背景精灵 x=187 y=6+i*18 → Menu +1）
        final net.neoforged.neoforge.items.ItemStackHandler upgrades =
            charger != null ? charger.getUpgradeContainer() : new net.neoforged.neoforge.items.ItemStackHandler(6);
        for (int i = 0; i < 6; i++) {
            // 范围充电器吃 速度/堆叠（充电速率）、范围升级（突破 100 格上限）与创造范围升级（无限范围）；
            // 自动合成对它无效，禁止放入
            addSlot(new cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot(upgrades, i, 188, 7 + i * 18,
                java.util.List.of("speed_upgrade", "stack_upgrade", "range_upgrade", "creative_range_upgrade")));
        }
        // 玩家主物品栏（3 行 9 列，背景精灵 (8,84) → Menu (9,85)）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 9 + col * 18, 85 + row * 18));
            }
        }
        // 快捷栏（背景精灵 (8,142) → Menu (9,143)）
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 9 + col * 18, 143));
        }
        // 充电目标高亮被配置关闭时，<b>开界面只提示一句</b>（actionbar；不刷屏、不每次充电都发）。
        // 只在服务端菜单构造时发一次：客户端菜单由二参工厂重建，charger == null 不会走到这里。
        if (charger != null && charger.getLevel() != null && !charger.getLevel().isClientSide()
            && inventory.player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
            && !cretae.cookiewyq.rs_create_compat.Config.rangeChargerHighlightTargets) {
            serverPlayer.displayClientMessage(
                net.minecraft.network.chat.Component.translatable(
                    "gui.rs_create_compat.range_charger.highlight_disabled"), true);
        }
    }

    public static RangeChargerMenu create(final int id, final Inventory inventory, final RangeChargerBlockEntity charger) {
        return new RangeChargerMenu(id, inventory, charger);
    }

    @Override
    public boolean clickMenuButton(final Player player, final int id) {
        if (charger != null && !charger.getLevel().isClientSide()) {
            switch (id) {
                case 0 -> charger.adjustRangeX(-1);
                case 1 -> charger.adjustRangeX(1);
                case 2 -> charger.adjustRangeY(-1);
                case 3 -> charger.adjustRangeY(1);
                case 4 -> charger.adjustRangeZ(-1);
                case 5 -> charger.adjustRangeZ(1);
                // 红石模式：与 RS 原版机器一致地循环 忽略 → 高电平 → 低电平（服务端权威）
                case RsccRedstoneMode.BUTTON_ID -> charger.setRedstoneMode(charger.getRedstoneMode().toggle());
                // 查看充电范围：先校验菜单仍有效（维度 / 距离 / 方块实体身份），再由 BE 回 S2C 快照
                case BUTTON_SHOW_RANGE -> {
                    if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                        && stillValid(player)) {
                        charger.sendRangeOutline(serverPlayer, containerId);
                    }
                }
                // 「高亮充电目标」开关：与上面完全同一道 stillValid（机器被拆 / 玩家走远都不许写）
                case BUTTON_TOGGLE_HIGHLIGHT -> {
                    if (player instanceof net.minecraft.server.level.ServerPlayer
                        && stillValid(player)) {
                        charger.toggleHighlightEnabled();
                    }
                }
                // 「过滤」界面**不**再走菜单按钮：它走独立 C2S 包 OpenChargerFilterPacket。
                // 第 98 轮复核发现旧实现同时留了「菜单按钮 8」与「独立包」两条通道，而按钮 8
                // 全仓没有任何发送点（死分支，自检还锚着它）—— 已删除，一条功能只留一条通道。
                default -> {
                }
            }
        }
        return true;
    }

    /** 当前红石模式（客户端读服务端同步值）。 */
    @Override
    public RedstoneMode rscc$getRedstoneMode() {
        return RedstoneModeSettings.getRedstoneMode(data.get(9));
    }

    /**
     * 本机「高亮充电目标」开关（数据槽 <b>12</b>，服务端权威）。
     *
     * <p>为什么走数据槽而不是再发一个 S2C 包：它就是一个 0/1，菜单同步通道本来每 tick 都在跑；
     * 为它单独加包只会让「按下按钮 → 按钮显示更新」多一次往返延迟。</p>
     */
    public boolean isHighlightEnabled() {
        return data.get(12) == 1;
    }

    /**
     * 上一次充电扫描中<b>因为目标已经充满、被直接跳过</b>的目标数（数据槽 <b>13</b>，服务端权威）。
     *
     * <h2>为什么要显示它（用户原话：「电量一直是满的就跳过这个，减少这种没有意义的次数」）</h2>
     * <p>「跳过」是个<b>没有视觉副作用</b>的优化：不充、不高亮、不发包 —— 玩家从画面上根本看不出
     * 它有没有生效。这个计数把它变成可见事实：界面上「已满跳过 N」那一行即 {@code data.get(13)}，
     * 客户端只负责显示，绝不自算（它统计的是服务端本 tick 的扫描结果，客户端无从得知）。</p>
     *
     * <p>计数单位 = 单个候选目标（一个方块 / 一个掉落物 / 一件携带物品各算 1；玩家档按每件物品判）。
     * 服务端每 tick 在 {@code RangeChargerBlockEntity#doCharging} 开头清零后重新累加，
     * 因此读到的是<b>这一 tick</b> 的数字，不做跨 tick 累计。</p>
     */
    public int getSkippedFullTargets() {
        return data.get(13);
    }

    /**
     * 服务端菜单持有的充电器；<b>客户端菜单恒为 {@code null}</b>。
     *
     * <p>客户端菜单由注册期的二参工厂 {@code (id, inventory) -> new RangeChargerMenu(id, inventory)}
     * 重建（普通 {@code MenuType}），根本没有方块实体引用 —— 这不是缺陷，而是原版容器菜单的
     * 双端模型：客户端只能看到同步过来的数据槽。</p>
     *
     * <p>因此本方法<b>只给服务端网络包处理体用</b>
     * （{@code OpenChargerFilterPacket} / {@code SetChargerFilterPacket} 在 {@code stillValid}
     * 之后取它去读写方块实体）；界面一律走数据槽 / S2C 快照，不得调用它。</p>
     */
    @Nullable
    public RangeChargerBlockEntity getCharger() {
        return charger;
    }

    public int getRangeX() {
        return data.get(0);
    }

    public int getRangeY() {
        return data.get(1);
    }

    public int getRangeZ() {
        return data.get(2);
    }

    public int getEnergy() {
        return data.get(3);
    }

    public int getMaxEnergy() {
        return data.get(4);
    }

    /** 当前实际充电的对象数量（方块/掉落物/玩家物品）。 */
    public int getTargetCount() {
        return data.get(5);
    }

    /**
     * 等效升级档数（{@code 速度数 + 堆叠数 × 3}，夹到 {@code [0, MAX_POWER]}）。
     * <p>值由方块实体 {@code RangeChargerBlockEntity#getUpgradeCount()} 给出 ——
     * 它走 {@link RsccRateLimiter#power(int, int)}，堆叠<b>已经</b>折算进去了，
     * 因此它<b>只用于显示</b>（tooltip 里的「等效档数」），绝不拿它再喂回
     * {@link RsccRateLimiter#rateSaturating(int, int, int)}：那会把 0..24 的折算值当成
     * 「速度升级个数」，被 {@code level()} 再夹到 6 ⇒ 界面最多显示 {@code base << 6}，
     * 而方块实体能到 {@code base << 24}（上一版就是这么分叉的）。</p>
     */
    public int getUpgradeCount() {
        return data.get(6);
    }

    /**
     * 当前充电速率（FE/t）——<b>界面与方块实体共用的唯一算法</b>。
     *
     * <p>为什么这个方法在菜单里而不是界面里（2026-10-17 修「界面显示负数」）：
     * 旧实现是界面自己算 {@code Config.rangeChargerChargeRate << menu.getUpgradeCount()}，
     * 在配置 ≥ 8192 FE/t + 满堆叠（power 18）时<b>溢出成负数</b>，而方块实体早就改走
     * {@link RsccRateLimiter#rateSaturating}（饱和到 {@link Integer#MAX_VALUE}）——
     * 同一个数字由两处各算一遍，必然分叉。现在两侧<b>调用的都是这一个方法</b>。</p>
     *
     * <p><b>2026-10-17 二次修正（把「看起来同源」变成「真的同一个输入」）</b>：
     * 上一版这里写的是 {@code rateSaturating(Config.rangeChargerChargeRate, getUpgradeCount(), 0)}——
     * 把<b>已经折算好</b>的等效档数（0..24）当成 {@code speedUpgrades} 传进去，
     * 而 {@code rateSaturating} 内部第一件事就是 {@code level(speed) = Math.clamp(speed, 0, 6)}：
     * 于是界面最多只能显示 {@code base << 6}，方块实体却能到 {@code base << 24}。
     * 现在数据槽 10/11 传的是<b>原始</b>速度 / 堆叠个数（与方块实体
     * {@code countUpgrades(SPEED_UPGRADE)} / {@code countUpgrades(STACK_UPGRADE)} 同一个值），
     * 本方法把它们原样传给<b>同一个</b> {@code rateSaturating} —— 两边是同一个纯函数的同一个输入，
     * 逐格相等，而不是「两段长得很像的代码」。</p>
     */
    public int getChargeRate() {
        return RsccRateLimiter.rateSaturating(
            cretae.cookiewyq.rs_create_compat.Config.rangeChargerChargeRate, data.get(10), data.get(11));
    }

    /** 范围升级数（用于 GUI 显示当前上限）。 */
    public int getRangeUpgradeCount() {
        return data.get(7);
    }

    /** 是否已放入创造范围升级（无限范围，隐藏输入框显示 ∞）。 */
    public boolean hasInfiniteRange() {
        return data.get(8) == 1;
    }

    /** 单轴范围上限：默认 100，每个范围升级 +{@link cretae.cookiewyq.rs_create_compat.Config#rangeChargerRangePerUpgrade}；无限时返回极大值。 */
    public int getMaxRange() {
        return hasInfiniteRange() ? Integer.MAX_VALUE - 1
            : 100 + data.get(7) * cretae.cookiewyq.rs_create_compat.Config.rangeChargerRangePerUpgrade;
    }

    /** 服务端设置指定轴的范围绝对值（客户端输入框 / ± 按钮发送）。 */
    public void setRangeValue(final int axis, final int value) {
        if (charger == null || charger.getLevel().isClientSide()) {
            return;
        }
        final int max = charger.getMaxRange();
        final int v = Math.max(1, Math.min(value, max));
        switch (axis) {
            case 0 -> charger.adjustRangeX(v - charger.getRangeX());
            case 1 -> charger.adjustRangeY(v - charger.getRangeY());
            case 2 -> charger.adjustRangeZ(v - charger.getRangeZ());
            default -> {
            }
        }
    }

    /**
     * 服务端菜单的充电器坐标；<b>客户端菜单上恒为 null</b>。
     *
     * <p>客户端菜单由注册期的二参工厂 {@code (id, inventory) -> new RangeChargerMenu(id, inventory)}
     * 重建（{@code RANGE_CHARGER_MENU} 是普通 {@code MenuType}，不是 {@code IMenuTypeExtension}），
     * 因此 {@code charger == null}。任何界面逻辑（例如「查看充电范围」的框选）都<b>不得</b>
     * 依赖这个方法取坐标 —— 坐标由服务端 S2C（{@code ShowChargerRangePacket}）下发。</p>
     */
    @Nullable
    public net.minecraft.core.BlockPos getChargerPos() {
        return charger != null ? charger.getBlockPos() : null;
    }

    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        ItemStack stack = ItemStack.EMPTY;
        final Slot slot = slots.get(index);
        if (slot != null && slot.hasItem()) {
            final ItemStack stackInSlot = slot.getItem();
            stack = stackInSlot.copy();
            if (index < 6) {
                // 插件槽 → 玩家背包
                if (!moveItemStackTo(stackInSlot, 6, 6 + 36, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // 玩家背包 → 插件槽
                if (!moveItemStackTo(stackInSlot, 0, 6, false)) {
                    return ItemStack.EMPTY;
                }
            }
            if (stackInSlot.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return stack;
    }

    @Override
    public boolean stillValid(final Player player) {
        return charger != null
            && charger.getLevel().getBlockEntity(charger.getBlockPos()) == charger
            && player.distanceToSqr(charger.getBlockPos().getX() + 0.5,
            charger.getBlockPos().getY() + 0.5,
            charger.getBlockPos().getZ() + 0.5) <= 64.0;
    }
}
