package cretae.cookiewyq.rs_create_compat.item;

import cretae.cookiewyq.rs_create_compat.Config;
import java.util.List;
import javax.annotation.ParametersAreNonnullByDefault;

import net.minecraft.ChatFormatting;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/**
 * <b>电动飞行背包</b>（{@code rs_create_compat:electric_jetpack}，第 99 轮新增）。
 *
 * <h2>它是什么</h2>
 * <ul>
 *     <li><b>创造式飞行</b>：空格起飞 / 双击起飞 / Shift 下降，按 FE 耗电（服务端权威，
 *     状态机与收回路径见 {@link cretae.cookiewyq.rs_create_compat.support.JetpackFlightHandler}）；</li>
 *     <li>容量 {@link Config#electricJetpackCapacity}（默认 5000 万 FE），充放电速率
 *     {@link Config#electricJetpackMaxTransfer}；电量<b>存在物品自己的组件里</b>（落盘），
 *     对外是标准的 NeoForge {@code Capabilities.EnergyStorage.ITEM}（见
 *     {@link cretae.cookiewyq.rs_create_compat.support.JetpackEnergyStorage}）；</li>
 *     <li><b>无耐久、无护甲值</b>：本类刻意<b>不</b>加 {@code Attributes.ARMOR}（用户硬性要求
 *     「不提供任何护甲值」），所以它虽然能穿在胸甲槽，但护甲条一点都不会涨。</li>
 * </ul>
 *
 * <h2>能装在哪三个位置（其它位置不生效）</h2>
 * <ol>
 *     <li>原版<b>胸甲槽</b>：靠 1.21.1 的 {@link Equipable}（注意 1.21.1 拼写是 <b>Equipable</b> 单 p，
 *     1.21.2 才改名 Equippable / 变成 {@code DataComponents.EQUIPPABLE} 组件）——右键即穿上 / 换下；</li>
 *     <li>Curios <b>{@code back}</b> 槽（背饰）；</li>
 *     <li>Curios <b>{@code chest}</b> 槽（胸饰）。</li>
 * </ol>
 * <p>「背饰 / 胸饰」两槽的<b>能否放入</b>由资源侧的物品标签
 * {@code data/curios/tags/item/back.json} 与 {@code chest.json} 决定（本类不参与）；
 * <b>是否生效</b>由 {@link cretae.cookiewyq.rs_create_compat.support.JetpackEquipped} 判定 ——
 * 背包 / 副手 / 主手 / 其它饰品槽里的一律<b>不</b>供电、<b>不</b>给飞行（tooltip 里写清，
 * 避免「我明明带着它怎么不能飞」）。</p>
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class ElectricJetpackItem extends Item implements Equipable {
    /**
     * 电量在物品 {@code CustomData} 上的键名。
     * <p><b>为什么用 {@code CustomData} 而不是自定义 {@code DataComponentType}</b>：
     * 自定义组件要在主类里多注册一个 {@code DeferredRegister<DataComponentType>}（本轮写域不允许改主类），
     * 而 {@code CustomData} 本身就是「随物品落盘 + 自动同步到客户端」的容器，语义完全等价。
     * 键名带本模组前缀语义（{@code Rscc}），不会和别的模组撞车。</p>
     */
    public static final String TAG_ENERGY = "RsccJetpackEnergy";

    /** 物品栏「耐久样式」能量条的颜色（青蓝，与 HUD 能量条同一色）。 */
    private static final int BAR_COLOR = 0x4FC3F7;

    /**
     * 配置尚未加载时的<b>安全回退值</b>（与 {@link Config} 里的默认值逐一相同）。
     * <p>为什么必须回退而不是直接用 0：{@code Config.onLoad} 之前读到的 {@code int} 字段是 0，
     * 而 {@link #getEnergy} 会把电量夹到 {@code [0, capacity]} —— 容量一旦是 0，<b>下一次写入就会把
     * 存档里的电量抹平</b>。这类「配置没加载好就毁数据」的路径不值得赌它不会发生，所以容量 / 速率 /
     * 耗电 / 低电阈值四个值全部走这层回退。</p>
     */
    private static final int DEFAULT_CAPACITY = 50_000_000;
    private static final int DEFAULT_TRANSFER = 100_000;
    /** 第 108 轮：默认耗电 32 → **8 FE/tick**（用户口径：「耗电量降到 8 FE/tick，这个值可以配置」）。 */
    private static final int DEFAULT_FLIGHT_COST = 8;
    /**
     * 第 109 轮：低电阈值从「剩余百分比」改成「**剩余可飞分钟**」（默认 30 分钟）。
     * <p>用户实机反馈：容量 5000 万 FE 时旧的「10%」= 500 万 FE，充到 200 万（4%）明明还能飞
     * 好几个小时，却一直在飞的时候被反复提示「电量不足」。按「还能飞多久」判定才符合直觉，
     * 所以阈值口径改成「剩余可飞分钟 &lt; 配置值」。</p>
     */
    private static final int DEFAULT_LOW_POWER_MINUTES = 30;

    /** 一分钟 = 20 tick/s × 60 s（「剩余可飞分钟」与「低电阈值 FE」的换算基准，只写这一处）。 */
    public static final int TICKS_PER_MINUTE = 20 * 60;

    public ElectricJetpackItem(final Item.Properties properties) {
        super(properties);
    }

    /**
     * 物品级 FE 能力注册（{@code Capabilities.EnergyStorage.ITEM}）。
     * <p>由 {@code RS_Create_Compat} 在 MOD 总线上注册一次（{@code modEventBus.addListener(ElectricJetpackItem::registerCapabilities)}）。
     * 放在物品类里而不是另开一个 {@code @EventBusSubscriber} 类：注册的只是「这一件物品」的能力，
     * 与物品自己的生命周期绑在一起最不容易走散（也和 {@code RangeChargerBlockEntity} 等既有方块实体
     * 「能力注册就写在自己类里」的做法一致）。</p>
     */
    public static void registerCapabilities(final net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) {
        event.registerItem(
            net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM,
            (stack, context) -> new cretae.cookiewyq.rs_create_compat.support.JetpackEnergyStorage(stack),
            cretae.cookiewyq.rs_create_compat.RS_Create_Compat.ELECTRIC_JETPACK.get()
        );
    }

    // ==================== 电量读写（唯一数据来源） ====================

    /** 物品当前容量（配置可在运行时重载；配置未加载 / 非法时用回退值，绝不返回 0）。 */
    public static int capacity() {
        return Config.electricJetpackCapacity > 0 ? Config.electricJetpackCapacity : DEFAULT_CAPACITY;
    }

    /** 充放电速率上限（FE/tick；配置未加载时用回退值）。 */
    public static int transfer() {
        return Config.electricJetpackMaxTransfer > 0 ? Config.electricJetpackMaxTransfer : DEFAULT_TRANSFER;
    }

    /** 飞行 / 悬停 / 下降每 tick 的耗电（FE；配置未加载时用回退值；下面再夹到 ≥ 1 防「免费飞行」）。 */
    public static int flightCost() {
        final int cost = Config.electricJetpackFlightCostPerTick > 0
            ? Config.electricJetpackFlightCostPerTick : DEFAULT_FLIGHT_COST;
        return Math.max(1, cost);
    }

    /**
     * 低电提示阈值（**剩余可飞分钟**，夹到 1..100000；配置未加载时用回退值 30）。
     * <p>第 109 轮用户口径：「需要一个阈值 = 还能飞行多长时间，单位分钟，可配置，预设 30 分钟；
     * 拿当前电量除以配置的耗电量换算成分钟，再和阈值比较，小于这个值才提示」。</p>
     */
    public static int lowPowerMinutes() {
        final int minutes = Config.electricJetpackLowPowerMinutes > 0
            ? Config.electricJetpackLowPowerMinutes : DEFAULT_LOW_POWER_MINUTES;
        return Math.clamp(minutes, 1, 100000);
    }

    /**
     * 低电阈值对应的电量（FE）= {@link #lowPowerMinutes()} × 1200 tick/min × {@link #flightCost()}。
     * <p>用 {@code long} 计算：分钟上限 100000 × 1200 × 耗电（可到 Integer.MAX_VALUE）会溢出 int；
     * 比较也一律用 long（{@code energyAfter < lowPowerThresholdFe()}），绝不先转 int 再比。</p>
     */
    public static long lowPowerThresholdFe() {
        return (long) lowPowerMinutes() * (long) TICKS_PER_MINUTE * (long) flightCost();
    }

    /**
     * 当前电量还能飞多少<b>整分钟</b>（向下取整，最小 0；耗电 ≥ 1 由 {@link #flightCost()} 保证，不会除零）。
     * <p>HUD / tooltip / 低电 title 副标题共用这一个函数，三处显示的数字不会互相矛盾。</p>
     */
    public static int remainingFlightMinutes(final int energy) {
        final long ticks = Math.max(0, energy) / (long) flightCost();
        return (int) Math.min(Integer.MAX_VALUE, ticks / (long) TICKS_PER_MINUTE);
    }

    /** 读电量：缺失键 = 0（新物品没电）；负值 / 超过容量的脏数据按边界夹住。 */
    public static int getEnergy(final ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        // 用 getUnsafe() 直读底层 tag（不额外复制一份）：本方法每 tick 会被调用若干次，
        // 与仓库其它地方（AdvancedRemoteTerminalItem#getMode）同一写法。
        final int raw = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
            .getUnsafe()
            .getInt(TAG_ENERGY);
        return Math.clamp(raw, 0, capacity());
    }

    /** 写电量（已夹到 {@code [0, capacity]}；写入量在合理范围内时 NBT 只有 4 字节）。 */
    public static void setEnergy(final ItemStack stack, final int energy) {
        if (stack.isEmpty()) {
            return;
        }
        final int clamped = Math.clamp(energy, 0, capacity());
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY,
            data -> data.update(tag -> tag.putInt(TAG_ENERGY, clamped)));
    }

    /**
     * 扣电（飞行每 tick 的消耗走这里，<b>不</b>走 {@code IEnergyStorage#extractEnergy}：
     * 那条路是给「别的机器要抽这罐电」用的，语义不同，混在一起会让「飞行耗电」变成可被外部限速影响）。
     *
     * @return 实际扣掉的 FE（电量不足时会少于 {@code amount}）
     */
    public static int consume(final ItemStack stack, final int amount) {
        if (stack.isEmpty() || amount <= 0) {
            return 0;
        }
        final int stored = getEnergy(stack);
        final int consumed = Math.min(stored, amount);
        if (consumed > 0) {
            setEnergy(stack, stored - consumed);
        }
        return consumed;
    }

    /** 某个栈是不是电动飞行背包（所有判定都走这一处，避免散落的 {@code instanceof} 口径不一致）。 */
    public static boolean isJetpack(final ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ElectricJetpackItem;
    }

    /**
     * <b>精确</b> FE 文本（tooltip / HUD 共用）：千分位逗号，{@code 2,000,000} / {@code 43,217,456}。
     * <p>第 109 轮用户口径：「定量要能显示准确的数字，比如 2,000,000，不要只显示 2M」——
     * 旧实现是 {@code %.1fM} / {@code %.1fK} 缩写，充到两百万也只看到「2.0M」，读不出准确值。
     * 这里固定 {@link java.util.Locale#ROOT}（不受玩家系统语言影响，永远是逗号分组），
     * 且<b>绝不</b>再出现 M/K 缩写（自检 round109 会钉死这一点）。</p>
     */
    public static String formatFeExact(final int fe) {
        return String.format(java.util.Locale.ROOT, "%,d", Math.max(0, fe));
    }

    // ==================== 胸甲槽：1.21.1 的 Equipable ====================

    @Override
    public EquipmentSlot getEquipmentSlot() {
        return EquipmentSlot.CHEST;
    }

    /**
     * 右键：把背包换到胸甲槽（与 {@code ArmorItem#use} 完全同一条原版逻辑
     * {@code Equipable#swapWithEquipmentSlot}：目标槽里已有物品时互换，不销毁任何东西）。
     * <p>只有服务端会真正改物品栏；客户端同样调用一次只是为了拿到 {@code SUCCESS} 的动画 / 音效
     * （原版护甲也是这个行为）。</p>
     *
     * <h2>为什么创造模式要单独接管（第 105 轮修）</h2>
     * <p>原版 {@code Equipable#swapWithEquipmentSlot} 在<b>创造模式</b>下是
     * {@code player.setItemSlot(槽, held.copy())} 且<b>手上保留原件</b>（「创造模式穿甲不消耗」的原版语义）。
     * 对护甲这没问题，但对飞行背包就是一次<b>真复制</b>：右键穿一次 = 手上 1 件 + 胸甲槽 1 件；
     * 而按 Q 只能丢快捷栏选中格，卡在胸甲槽的那份永远丢不掉 —— 玩家看到的就是
     * 「掉了一份、身上/手上还有一份、再扔扔不出去」的幽灵现象。
     * 因此创造模式改走「和生存完全一致」的语义：<b>移动</b>（手上那件进胸甲槽，原来穿的那件换回手上），
     * 不产生第二份。</p>
     */
    @Override
    public InteractionResultHolder<ItemStack> use(final Level level, final Player player,
                                                  final InteractionHand hand) {
        if (!player.isCreative()) {
            // 生存 / 冒险：原版已经把手上那件「移动」进胸甲槽（copyAndClear），不会复制。
            return swapWithEquipmentSlot(this, level, player, hand);
        }
        // ---- 创造模式：移动而不是复制 ----
        final ItemStack held = player.getItemInHand(hand);
        final EquipmentSlot slot = getEquipmentSlot();
        final ItemStack worn = player.getItemBySlot(slot);
        if (ItemStack.matches(held, worn)) {
            // 手上那件与穿着的那件完全相同（同物品同组件）⇒ 原版也会 pass，不做事。
            return InteractionResultHolder.pass(held);
        }
        if (!level.isClientSide) {
            // 顺序很重要：先把手上那件**复制一份**写进胸甲槽，再把旧件（或空栈）写回手槽。
            // 最后一步会覆盖手槽里 held 的本体 ⇒ 净效果是「移动 1 件」，不存在第二份。
            player.setItemSlot(slot, held.copy());
            player.setItemInHand(hand, worn);
            player.awardStat(Stats.ITEM_USED.get(this));
        }
        return InteractionResultHolder.sidedSuccess(held, level.isClientSide());
    }

    // ==================== 物品栏能量条 / tooltip ====================

    /** 没充满时在物品格上显示一根能量条（充满就不画，和 RS 终端「满电不占位置」一致）。 */
    @Override
    public boolean isBarVisible(final ItemStack stack) {
        return getEnergy(stack) < capacity();
    }

    @Override
    public int getBarWidth(final ItemStack stack) {
        final int capacity = capacity();
        return Math.round(13.0F * getEnergy(stack) / capacity);
    }

    @Override
    public int getBarColor(final ItemStack stack) {
        return BAR_COLOR;
    }

    @Override
    public void appendHoverText(final ItemStack stack,
                                final TooltipContext context,
                                final List<Component> tooltip,
                                final TooltipFlag flag) {
        final int energy = getEnergy(stack);
        // 第 109 轮：电量显示**精确值**（千分位），不再用 2.0M 这种缩写。
        tooltip.add(Component.translatable("item.rs_create_compat.electric_jetpack.tooltip.energy",
                formatFeExact(energy), formatFeExact(capacity()))
            .withStyle(ChatFormatting.AQUA));
        // 剩余可飞分钟：和 HUD / 低电提示同一个函数，玩家不用自己换算。
        tooltip.add(Component.translatable("item.rs_create_compat.electric_jetpack.tooltip.flight_left",
                remainingFlightMinutes(energy))
            .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("item.rs_create_compat.electric_jetpack.tooltip.slots")
            .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.rs_create_compat.electric_jetpack.tooltip.flight")
            .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.rs_create_compat.electric_jetpack.tooltip.cost",
                flightCost())
            .withStyle(ChatFormatting.DARK_GRAY));
        // 阈值口径写清楚：低于「可飞 N 分钟」才提示（N 可配置），避免玩家以为提示坏了。
        tooltip.add(Component.translatable("item.rs_create_compat.electric_jetpack.tooltip.low_power",
                lowPowerMinutes(), formatFeExact((int) Math.min(Integer.MAX_VALUE, lowPowerThresholdFe())))
            .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("item.rs_create_compat.electric_jetpack.tooltip.charge")
            .withStyle(ChatFormatting.DARK_GRAY));
    }
}
