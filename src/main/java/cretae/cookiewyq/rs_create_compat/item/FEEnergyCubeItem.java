package cretae.cookiewyq.rs_create_compat.item;

import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.FEEnergyCubeBlockEntity;
import cretae.cookiewyq.rs_create_compat.support.EnergyCubeItemEnergy;
import java.util.List;
import javax.annotation.ParametersAreNonnullByDefault;

import net.minecraft.ChatFormatting;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jetbrains.annotations.Nullable;

/**
 * <b>FE 能量立方的物品形态</b>（{@code rs_create_compat:fe_energy_cube}，第 110 轮新增）。
 *
 * <h2>用户口径（第 110 条实机反馈）</h2>
 * <blockquote>「这个能量立方应该和控制器一样，它有那个能量条（显示在下面，类似耐久条那样，储存的是电量）；
 * 但现在我拿在手上它是没有电量条的，它得是有电量条的，因为他没有电量条拿在手上也充不了电、也看不到他有多少电量。
 * 这个东西拿在手上应该是能够显示当前电量的，而且不管有没有按下 shift 键都应该得显示它当前的电量。」</blockquote>
 * 三条都落在这里：
 * <ol>
 *     <li><b>拿在手上能充电</b>：注册 {@code Capabilities.EnergyStorage.ITEM}（{@link #registerCapabilities}，
 *     实现体见 {@link EnergyCubeItemEnergy}）⇒ 范围充电器 / 其它模组的充电源扫玩家物品栏时能真的给它充；
 *     反过来它也能被抽（单块时 {@code canExtract} 恒 true），与方块形态「被注入 / 被抽取永不看门控」
 *     同一口径；<b>多块一堆时充放一律拒绝</b>（见下面「安全边界」，那是防复制，不是门控）；</li>
 *     <li><b>物品栏里有电量条</b>：{@link #isBarVisible} / {@link #getBarWidth} / {@link #getBarColor} ——
 *     就是原版「耐久条」那根 13 px 条，改成画电量；</li>
 *     <li><b>不按 Shift 也看得到电量</b>：{@link #appendHoverText} 里无条件追加一行精确电量
 *     （{@code 2,000,000 / 50,000,000 FE}），<b>没有任何 {@code TooltipFlag#isAdvanced} / Shift 门控</b>。</li>
 * </ol>
 *
 * <h2>电量存在哪：和方块<b>同一个</b> NBT 键（这是本类唯一的硬约束）</h2>
 * <p>物品电量<b>不是</b>本类另开的存储，而是直接读写方块物品自带的
 * {@code DataComponents.BLOCK_ENTITY_DATA} 里 {@link FEEnergyCubeBlockEntity#TAG_ENERGY} 那一个键：
 * <ul>
 *     <li><b>挖下来</b>：{@code FEEnergyCubeBlock#getDrops} → {@code BlockEntity#saveToItem} →
 *     {@code BlockItem.setBlockEntityData}，写的正是 {@code saveAdditional} 里的
 *     {@code TAG_ENERGY}（{@code "Energy"}，见 {@code FEEnergyCubeBlockEntity#saveAdditional}）；</li>
 *     <li><b>放下去</b>：原版 {@code BlockItem#updateCustomBlockEntityTag} 校验 {@code id} 后
 *     {@code loadWithComponents} → {@code loadAdditional} 读回<b>同一个</b> {@code TAG_ENERGY}；</li>
 *     <li><b>拿在手上</b>：本类读写<b>同一份</b>键（{@link #getEnergy} / {@link #setEnergy}）。
 *     因此「手上充进去的电，放下方块后就是方块的电」——不存在「放下就丢电」的第二套存储。
 *     若将来有人把这里换成自有键（例如 {@code "RsccCubeEnergy"}），这条链路会立刻断掉，
 *     自检 {@code tools/selfcheck_round110_energy_cube_item.py} 会用两条相互独立的判据钉死它。</li>
 * </ul>
 * <p><b>写入用 {@link BlockItem#setBlockEntityData}</b>（而不是 {@code stack.set(BLOCK_ENTITY_DATA,
 * CustomData.of(tag))}）：这个组件在 1.21.1 的持久化 codec 需要 {@code id} 字段（= 方块实体类型 id），
 * 手写 {@code CustomData} 很容易写出「存档里 id 丢了 ⇒ 放下时 tag 校验失败 ⇒ 电量丢失」的物品。
 * 走原版 API 就由它负责 {@code remove("id") → addEntityType(...)} 的成对处理，与
 * {@code saveToItem} 是同一条路径 —— 这也是「共用同一个键」的一部分，不只是键名相同。</p>
 * <p>上述 codec 约束是<b>实测</b>而非推测（记录在这里，免得后人再猜一次）：反编译 1.21.1 的
 * {@code DataComponents} 可见 {@code block_entity_data} 指向 {@code lambda$static$39}，它用的是
 * {@code CustomData.CODEC_WITH_ID} + {@code CustomData.STREAM_CODEC}（networkSynchronized）；
 * 而 {@code CODEC_WITH_ID} 的解码校验就是 {@code tag.contains("id", 8)}（TAG_STRING），缺了直接
 * {@code DataResult.error}。顺带得到第二个结论：这个组件<b>会同步到客户端</b>，所以物品栏 tooltip
 * 读到的是服务端那份真实电量，不需要另开一个「电量同步包」。</p>
 *
 * <h2>物品形态的「集群 / 相邻共享」语义（避免误解）</h2>
 * <p>方块形态下，相邻的能量立方会组成一个集群、<b>容量池相加</b>（见 {@code support/EnergyCubeCluster}）；
 * 但<b>物品形态没有集群</b>：它只是「一块方块 + 它自己那一份电量」，容量恒为
 * {@link Config#feEnergyCubeCapacity}（= 单块容量，<b>不是</b> N 块之和），因为「物品旁边」没有方块邻居。
 * 物品放下之后才按方块的规则与相邻立方并池。这一点写在 tooltip 里（
 * {@code item.rs_create_compat.fe_energy_cube.tooltip.cluster}），否则玩家会问
 * 「我手上这根条为什么不是 5000 万 × 3」。</p>
 *
 * <h2>安全边界：多块一堆时不能充放电（写进 tooltip）</h2>
 * <p>电量写在 {@code BLOCK_ENTITY_DATA} 组件上，而组件是<b>整堆共享一份</b>的。若允许一堆 64 块充到
 * 100,000 FE，立刻出现两条复制路径：<b>拆堆</b>（{@code ItemStack#split → copyWithCount} 复制组件 ⇒
 * 两堆各 100,000）与<b>放置</b>（{@code BlockItem#updateCustomBlockEntityTag} 读的是整堆那一份组件，
 * 放一块后 {@code shrink(1)} 并不清组件 ⇒ 64 块各 100,000）。因此能力视图在 {@code count > 1} 时
 * 一律拒绝充放（见 {@link EnergyCubeItemEnergy} 的「堆叠守卫」），tooltip 在这种状态下追加一行
 * ({@code ...tooltip.stacked}) 说明「拆成单块才能充」。</p>
 * <p><b>为什么不去改堆叠上限</b>：方块物品 64 个一堆地携带 / 摆放是既有玩法（用户没要求改），
 * 本轮用「按堆叠状态拒绝充放」而不是「禁止堆叠」来堵漏洞 —— 空立方照旧能成堆，单块能充，
 * 两者不冲突。</p>
 *
 * <h2>颜色出处（{@link #BAR_COLOR}）</h2>
 * <p>{@code 0x18C8AA} = {@code tools/gen_fe_energy_cube_assets.py} 里的 {@code ACCENT}
 * （{@code (0x18, 0xC8, 0xAA, 255)}，「青绿主色」，用于立方方块侧面的电量窗口与底面输出接缝）。
 * 用方块自己的强调色而不是随便挑一个，是为了「物品栏里那根条 = 这块方块的电池颜色」；
 * 同时刻意与飞行背包的 {@code 0x4FC3F7}（青蓝）区分，避免两种电池在物品栏里看着像同一种东西。</p>
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class FEEnergyCubeItem extends RsccHelpBlockItem {
    /**
     * 配置尚未加载时的<b>安全回退容量</b>（与 {@link Config} 里的 {@code feEnergyCubeCapacity} 默认值相同）。
     * <p>为什么必须回退而不是直接用 0：{@code Config.onLoad} 之前读到的 {@code int} 字段是 0，
     * 而 {@link #getEnergy} 会把电量夹到 {@code [0, capacity]} —— 容量一旦是 0，<b>下一次写入就会把
     * 物品里已有的电量抹平</b>。与 {@code ElectricJetpackItem#capacity()} 同一个教训、同一种写法。</p>
     */
    private static final int DEFAULT_CAPACITY = 50_000_000;

    /** 配置尚未加载时的<b>安全回退速率</b>（与 {@link Config#feEnergyCubeMaxTransfer} 默认值相同）。 */
    private static final int DEFAULT_TRANSFER = 100_000;

    /**
     * 物品栏「耐久样式」电量条的颜色：青绿 {@code 0x18C8AA}。
     * <p>出处：{@code tools/gen_fe_energy_cube_assets.py} 的 {@code ACCENT = (0x18, 0xC8, 0xAA, 255)}。
     * 见类注释「颜色出处」。</p>
     */
    private static final int BAR_COLOR = 0x18C8AA;

    public FEEnergyCubeItem(final Block block, @Nullable final Component helpText) {
        super(block, helpText);
    }

    /**
     * 物品级 FE 能力注册（{@code Capabilities.EnergyStorage.ITEM}），由 {@code RS_Create_Compat}
     * 在 MOD 总线上<b>注册一次</b>（{@code modEventBus.addListener(FEEnergyCubeItem::registerCapabilities)}）。
     *
     * <p>与飞行背包（{@code ElectricJetpackItem#registerCapabilities}）完全同一模式：能力实例只是
     * 「一个绑定到某份存活 {@link ItemStack} 的视图」（每次 {@code getCapability} 调用 new 一个），
     * 读写每次都落到<b>当前那份栈</b>上。缓存实例会在物品被复制 / 换槽 / 拆堆后指向旧栈，
     * 写出「明明充了电却没了」的幽灵数据（{@code JetpackEnergyStorage} 类注释里有完整推理）。</p>
     *
     * <p><b>为什么注册在物品类里而不是另开 {@code @EventBusSubscriber}</b>：注册的只是「这一件物品」的能力，
     * 与物品自己的生命周期绑在一起最不容易走散（也和 {@code RangeChargerBlockEntity} 等
     * 「能力注册就写在自己类里」的既有做法一致）。</p>
     */
    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerItem(
            Capabilities.EnergyStorage.ITEM,
            (stack, context) -> new EnergyCubeItemEnergy(stack),
            RS_Create_Compat.FE_ENERGY_CUBE_ITEM.get()
        );
    }

    // ==================== 电量读写（唯一数据来源 = 方块实体的同一个 TAG） ====================

    /**
     * 物品当前容量（配置可在运行时重载；配置未加载 / 非法时用回退值，绝不返回 0）。
     * <p>注意这是<b>单块</b>容量：物品形态没有相邻集群，不与任何邻居并池（见类注释）。</p>
     */
    public static int capacity() {
        return Config.feEnergyCubeCapacity > 0 ? Config.feEnergyCubeCapacity : DEFAULT_CAPACITY;
    }

    /** 充放电速率上限（FE/tick；与方块六面能力视图用的是<b>同一个</b>配置项 {@link Config#feEnergyCubeMaxTransfer}）。 */
    public static int transfer() {
        return Config.feEnergyCubeMaxTransfer > 0 ? Config.feEnergyCubeMaxTransfer : DEFAULT_TRANSFER;
    }

    /**
     * 读物品电量：读的是方块物品 {@code BLOCK_ENTITY_DATA} 里的
     * {@link FEEnergyCubeBlockEntity#TAG_ENERGY}（与方块落盘 / 放置还原<b>同一个键</b>）。
     * <p>缺失键 = 0（新造出来的空电池 / 挖下来时电量本来就是 0 —— {@code getDrops} 在存量为 0 时
     * 干脆不写 tag）；负值 / 超过容量的脏数据按边界夹住，不让 tooltip 出现 {@code -1 FE} 这种读数。</p>
     * <p>用 {@code getUnsafe()} 直读底层 tag（不额外复制一份）：本方法会被 tooltip /
     * 物品栏渲染每帧调用多次，与 {@code ElectricJetpackItem#getEnergy}、
     * {@code AdvancedRemoteTerminalItem#getMode} 同一写法。<b>只读</b>不会污染共享 tag。</p>
     */
    public static int getEnergy(final ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        final CompoundTag tag = stack.getOrDefault(DataComponents.BLOCK_ENTITY_DATA, CustomData.EMPTY)
            .getUnsafe();
        return Math.clamp(tag.getInt(FEEnergyCubeBlockEntity.TAG_ENERGY), 0, capacity());
    }

    /**
     * 写物品电量（已夹到 {@code [0, capacity]}）。
     *
     * <p><b>必须走 {@link BlockItem#setBlockEntityData}</b>（见类注释：它会成对处理 {@code id}），
     * 且<b>只改 {@code TAG_ENERGY} 这一个键</b> —— 先 {@code copyTag()} 拿到整份现有 BE 数据的副本，
     * 改完再整份写回，这样方块实体在同一个 tag 里存的别的东西（将来的字段、别的模组挂的数据）
     * 不会因为我们充电而被抹掉。</p>
     *
     * <p>「空电池 + 本来就没有 BE 数据」时直接返回：和 {@code FEEnergyCubeBlock#getDrops}
     * 「空电池不写 tag」保持对称，不因为一次充放到 0 就给玩家造一个只有
     * {@code {Energy:0, id:...}} 的物品。</p>
     */
    public static void setEnergy(final ItemStack stack, final int energy) {
        if (stack.isEmpty()) {
            return;
        }
        final int clamped = Math.clamp(energy, 0, capacity());
        final CompoundTag existing = stack.getOrDefault(DataComponents.BLOCK_ENTITY_DATA, CustomData.EMPTY)
            .copyTag();
        if (clamped <= 0 && existing.isEmpty()) {
            return;
        }
        existing.putInt(FEEnergyCubeBlockEntity.TAG_ENERGY, clamped);
        BlockItem.setBlockEntityData(stack, RS_Create_Compat.FE_ENERGY_CUBE_BLOCK_ENTITY.get(), existing);
    }

    // ==================== 物品栏电量条（「类似耐久条」） ====================

    /**
     * 没充满时在物品格上显示一根能量条；<b>充满就不画</b>。
     * <p>与飞行背包 / RS 终端同一口径（「满电不占位置」）：玩家一眼就能看出哪些电池还是空的，
     * 不会满屏都是满格条。</p>
     */
    @Override
    public boolean isBarVisible(final ItemStack stack) {
        return getEnergy(stack) < capacity();
    }

    /** 条长 13 px（原版耐久条宽度），按「存量 / 容量」线性映射。 */
    @Override
    public int getBarWidth(final ItemStack stack) {
        final int capacity = capacity();
        return Math.round(13.0F * getEnergy(stack) / capacity);
    }

    /** 条的填充色 = 立方方块的青绿强调色（出处见 {@link #BAR_COLOR} / 类注释）。 */
    @Override
    public int getBarColor(final ItemStack stack) {
        return BAR_COLOR;
    }

    // ==================== tooltip（常显，不按 Shift） ====================

    /**
     * tooltip：<b>常显</b>当前电量，不看 Shift、不看 {@code TooltipFlag#isAdvanced}。
     *
     * <p><b>为什么先调 {@code super.appendHoverText}</b>：父类链路是
     * {@code RsccHelpBlockItem → RS BaseBlockItem → BlockItem → Item}。RS 的「常显帮助」其实走的是
     * {@code BaseBlockItem#getTooltipImage}（返回 {@code HelpTooltipComponent} 的 image tooltip），
     * <b>不是</b> {@code appendHoverText}；所以本类必须<b>不覆写</b> {@code getTooltipImage}，
     * 帮助文本才会照旧常显。这里调 super 是为了不丢掉父链路将来在 {@code appendHoverText} 里加的行
     * （RS 版本升级时可能加），属于「不要覆盖丢掉父类行为」的兜底。</p>
     */
    @Override
    public void appendHoverText(final ItemStack stack,
                                final TooltipContext context,
                                final List<Component> tooltip,
                                final TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        final int energy = getEnergy(stack);
        // 精确值（千分位，不用 2.0M 缩写）：第 109 轮定下的口径，本类直接复用
        // ElectricJetpackItem#formatFeExact，绝不另造一种格式 —— 同一个模组里两种 FE 数字写法
        // 会让玩家以为是两个不同的量。
        tooltip.add(Component.translatable("item.rs_create_compat.fe_energy_cube.tooltip.energy",
                ElectricJetpackItem.formatFeExact(energy), ElectricJetpackItem.formatFeExact(capacity()))
            .withStyle(ChatFormatting.AQUA));
        // 物品只代表这一块；放下后才与相邻立方并池（否则玩家会以为手上这根条也该是 N 块之和）。
        tooltip.add(Component.translatable("item.rs_create_compat.fe_energy_cube.tooltip.cluster")
            .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.rs_create_compat.fe_energy_cube.tooltip.charge",
                ElectricJetpackItem.formatFeExact(transfer()))
            .withStyle(ChatFormatting.DARK_GRAY));
        // 多块一堆时不能充放电（组件整堆共享一份 ⇒ 会复制电量，见 EnergyCubeItemEnergy 的「堆叠守卫」）。
        // 这一行只在真的堆叠时出现；判据是「堆叠数」而不是任何按键 / isAdvanced，仍然符合
        // 用户「不按 Shift 也看得到电量」的口径 —— 电量那一行在上面已经无条件加过了。
        if (stack.getCount() > 1) {
            tooltip.add(Component.translatable("item.rs_create_compat.fe_energy_cube.tooltip.stacked")
                .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
