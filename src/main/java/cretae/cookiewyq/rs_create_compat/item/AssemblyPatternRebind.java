package cretae.cookiewyq.rs_create_compat.item;

import com.refinedmods.refinedstorage.api.network.Network;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.SequenceExecutionChamberBlock;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import cretae.cookiewyq.rs_create_compat.data.RecipeTypeNames;
import cretae.cookiewyq.rs_create_compat.data.SequencePatternData;
import cretae.cookiewyq.rs_create_compat.network.AssemblyPatternRebindOpenPacket;
import cretae.cookiewyq.rs_create_compat.network.AssemblyPatternStepMachinePacket;
import cretae.cookiewyq.rs_create_compat.network.SyncChamberListPacket;
import cretae.cookiewyq.rs_create_compat.support.RsccAssemblyDebug;
import cretae.cookiewyq.rs_create_compat.support.UnitManagerSources;
import cretae.cookiewyq.rs_create_compat.support.UnitPatternDedupe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 「已在手上的总样板改绑机器」——服务端权威实现（触发、校验、改写、旧引用清理全在这里）。
 *
 * <h2>为什么要有它（用户原话）</h2>
 * <p>用户的模型是：<b>单元样板不绑定机器、想放哪就放哪；总样板必须绑定机器</b>
 * （同一种配方类型可能有多台机器持有，必须明确是哪一台）。痛点是
 * <i>「每一次都在做一个样板太麻烦了，我想着能不能在制作好的样板上进行更改」</i> ——
 * 在此之前只有两条路：①回终端重排一遍并重新生成（消耗 RS 样板，最烦的一步）；
 * ②自动合成监视器里的「更换机器」，但它<b>只对正在跑的任务</b>有效（见
 * {@code AssemblyWatchdog#changeStepMachine}，按 taskId + 产物定位样板），
 * 手上/库里一张没在跑的总样板根本改不动。</p>
 *
 * <h2>绑定到底存在哪一侧（本实现的事实前提）</h2>
 * <p>机器指派存在<b>物品一侧</b>：{@link SequencePatternData.UnitEntry#machinePos()} /
 * {@code machineName()}（NBT 键 {@code MachinePos} / {@code MachineName}，见
 * {@link SequencePatternData} 的读写）。执行舱的 {@code unitSlots} 里放的是<b>单元样板</b>
 * （配方 id + 步序 + 配方类型），它决定的是「哪台机器<b>拥有</b>这一步」，
 * 与「这一步被<b>指派</b>给谁」是两份数据。因此改绑必须<b>两侧一起改</b>：
 * 只改物品 → 指派指向 B，而 B 没有该步的单元样板 ⇒ 过渡件被 B 拉走却加工不了（静默卡住）；
 * 只改执行舱 → 属主换了，总样板还指着 A ⇒ 掉线告警与类别归属全部对不上。
 *
 * <h2>不变量（本类只做这几件事，且一步都不放松）</h2>
 * <ol>
 *     <li><b>原地改写</b>：只改玩家手里那一件物品的数据（不复制、不销毁、数量不变）；</li>
 *     <li><b>候选组一律原样保留</b>：重写 {@link SequencePatternData.UnitEntry} 时把
 *     {@code inputCandidates} / {@code inputFluid} / 主原料候选 ({@code ingredientCandidates})
 *     全部带过去 —— 漏掉就会静默把「铁粒/锌粒」退化成「只要铁粒」（与既有 5-arg 构造器的坑同源）；</li>
 *     <li><b>不留悬空引用</b>：找不到该步的单元样板时<b>直接拒绝</b>（绝不先写 {@code machinePos}），
 *     因为「指派到一台没有该步样板的机器」在实机上就是产线静默停摆；</li>
 *     <li><b>一步只有一个属主</b>：迁移时把别处的同一步样板收走（同网络内），
 *     目标已持有则只清理重复张 —— 与 {@link UnitPatternDedupe} 的「同配方同步序只有一张」口径一致。</li>
 * </ol>
 */
public final class AssemblyPatternRebind {
    /** 文案键前缀（全部键都是 gui.* 头段，action bar 上也用同一批键，见工程惯例）。 */
    private static final String LANG = "gui.rs_create_compat.assembly_pattern_rebind.";

    private AssemblyPatternRebind() {
    }

    // ==================== 触发：手持总样板右键空气 ====================

    /**
     * 取网络时以玩家为中心的搜索半径（方块）。纯只读方块状态扫描，不加载区块。
     * <p>8 格足够覆盖「站在机器跟前右键」这一现实场景，同时把单次扫描控制在 17³ 格以内
     * （约 5000 次 {@code getBlockState}，只在右键那一下发生，代价可忽略）。</p>
     */
    private static final int NEARBY_RADIUS = 8;

    /**
     * 触发入口（<b>仅服务端</b>）：把「这张总样板 + 玩家身边那台执行舱所在网络」的权威快照发给玩家，
     * 客户端据此弹出改绑界面。本方法<b>不改动任何东西</b> —— 真正的写入只在
     * {@link #apply(ServerPlayer, AssemblyPatternStepMachinePacket)} 里，且必须由界面确认后再发一次包。
     *
     * <p>为什么不做成「右键即改绑」：同一种配方类型下可能有多台机器、一条样板里也可能有
     * <b>多个同类步骤</b>（例如两步冲压分给两台冲压机），一次点击无法表达「改哪一步」，
     * 猜错就是错绑。因此右键只负责「打开」，步与机器都由玩家在界面里明确指定。</p>
     *
     * <p>触发是<b>右键空气</b>（见 {@code SequenceAssemblyPatternItem#use}）：完全不经过方块交互，
     * 因此没有 1.21.1「{@code useWithoutItem} 先消费掉动作」那个坑，也不需要目标方块。</p>
     */
    public static void openFor(final ServerPlayer player, final InteractionHand hand) {
        openFor(player, hand, null);
    }

    /**
     * 触发入口的完整形态：{@code anchor} 显式给定执行舱时用它的网络，为 {@code null} 时
     * 按玩家位置取网络（见 {@link #nearbyNetwork(ServerPlayer)}）。
     *
     * <p><b>为什么保留 anchor 这一路</b>：{@link #apply} 写入成功后要发一份权威快照刷新界面，
     * 那一刻「目标机器」是确知的，直接用它的网络比再按玩家位置猜更准确（玩家可能没动，但也没必要猜）。</p>
     */
    public static void openFor(final ServerPlayer player, final InteractionHand hand,
                               @Nullable final SequenceExecutionChamberBlockEntity anchor) {
        final ItemStack held = player.getItemInHand(hand);
        final UUID id = fingerprint(held);
        if (id == null) {
            return; // 手上不是总样板：什么都不做（调用点已判过，这里是兜底）
        }
        final ServerLevel level = player.serverLevel();
        final HolderLookup.Provider registries = level.registryAccess();
        final SequencePatternData.AssemblyData assembly = SequencePatternData.readAssembly(held, registries);
        if (assembly == null || assembly.units().isEmpty()) {
            // 空白总样板 / 没有任何步：没有步骤可改，明确说一句（否则玩家右键后「什么都没发生」）。
            // 与 apply 的「no_step」一样，这是拒绝 + 提示，绝不开一个空界面。
            send(player, Component.translatable(LANG + "empty_pattern"));
            return;
        }
        // 候选机器 = 那台执行舱<b>所在网络</b>里的全部执行仓（客户端按每一步的配方类型过滤）。
        // 为什么锚在网络而不是玩家：执行舱可能与其他机器同网络但玩家站在别处，
        // 「能接手这一步的机器」只由网络决定。
        final Network network = anchor == null ? nearbyNetwork(player) : UnitPatternDedupe.networkOf(anchor);
        if (network == null) {
            // 附近一台执行舱都没有 / 它们都没接入网络：取不到网络就无从列出候选 ⇒ 拒绝 + 提示
            send(player, Component.translatable(LANG + "no_chamber_near"));
            return;
        }
        final List<SyncChamberListPacket.Entry> chambers = chamberEntries(network);
        if (chambers.isEmpty()) {
            // 网络是有的，但里面没有一台「配好名字 + 配方类型」的执行舱：同样列不出候选
            send(player, Component.translatable(LANG + "no_chamber"));
            return;
        }
        PacketDistributor.sendToPlayer(player, new AssemblyPatternRebindOpenPacket(
            hand.ordinal(), id, stepEntries(assembly), chambers));
    }

    /**
     * 玩家身边最近的、<b>已接入网络</b>的执行舱所在的 RS 网络（一台都找不到时返回 {@code null}）。
     *
     * <h2>没有目标方块之后，网络从哪里来（本方法就是答案）</h2>
     * <p>改绑的候选机器必须是「本网络内的序列执行舱」，而右键空气没有方块可以锚。可用的权威来源
     * 只有玩家身边真实存在的执行舱：它在哪个网络，就用哪个网络 —— 与「右键执行舱」那条旧入口
     * 取的是同一个东西（{@link UnitPatternDedupe#networkOf}，与去重 / 归属同一口径），
     * 只是「哪台舱」由玩家位置决定而不是由点击决定。</p>
     *
     * <p><b>确定性</b>：先按「距离平方升序 → 坐标字典序」把所有候选排好，再取第一台有网络的，
     * 因此同一站位、同一世界状态的结果永远一致；一台都没接网络时返回 {@code null}（调用方拒绝 + 提示）。</p>
     *
     * <p><b>成本</b>：只读已加载区块的方块状态（先 {@code isLoaded} 再取），
     * 最近一次右键最多扫 (2×{@link #NEARBY_RADIUS}+1)³ 格，不触发区块加载、不写任何状态。</p>
     */
    @Nullable
    private static Network nearbyNetwork(final ServerPlayer player) {
        final ServerLevel level = player.serverLevel();
        final BlockPos center = player.blockPosition();
        final List<SequenceExecutionChamberBlockEntity> found = new ArrayList<>();
        for (int dx = -NEARBY_RADIUS; dx <= NEARBY_RADIUS; dx++) {
            for (int dy = -NEARBY_RADIUS; dy <= NEARBY_RADIUS; dy++) {
                for (int dz = -NEARBY_RADIUS; dz <= NEARBY_RADIUS; dz++) {
                    final BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.isLoaded(pos)
                        || !(level.getBlockState(pos).getBlock()
                            instanceof SequenceExecutionChamberBlock)) {
                        continue; // 未加载 / 不是执行舱：一律跳过（绝不因取网络而加载区块）
                    }
                    if (level.getBlockEntity(pos) instanceof SequenceExecutionChamberBlockEntity chamber) {
                        found.add(chamber);
                    }
                }
            }
        }
        found.sort(Comparator
            .comparingLong((SequenceExecutionChamberBlockEntity chamber) ->
                distanceSq(center, chamber.getBlockPos()))
            .thenComparingLong(chamber -> chamber.getBlockPos().asLong()));
        for (final SequenceExecutionChamberBlockEntity chamber : found) {
            final Network network = UnitPatternDedupe.networkOf(chamber);
            if (network != null) {
                return network;
            }
        }
        return null;
    }

    /** 两坐标之间的欧氏距离平方（只用于「谁更近」的确定性排序）。 */
    private static long distanceSq(final BlockPos a, final BlockPos b) {
        final long dx = (long) a.getX() - b.getX();
        final long dy = (long) a.getY() - b.getY();
        final long dz = (long) a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    // ==================== 写入：界面确认后的一次性改绑 ====================

    /**
     * 服务端权威写入（C2S 包的唯一处理体）。
     *
     * <p>全部拒绝路径都<b>先判后写</b>、且拒绝时一个字节都不改；成功时按「先腾位置、再搬样板、
     * 最后改物品」的顺序落盘，保证任何一步失败都不会留下「指派已改、样板没搬」的中间态。</p>
     */
    public static void apply(final ServerPlayer player, final AssemblyPatternStepMachinePacket packet) {
        final InteractionHand hand = handOf(packet.handOrdinal());
        if (hand == null) {
            return;
        }
        final ItemStack held = player.getItemInHand(hand);
        if (!(held.getItem() instanceof SequenceAssemblyPatternItem)) {
            send(player, Component.translatable(LANG + "no_pattern"));
            return;
        }
        // 指纹校验：界面打开后玩家可能把样板换掉 / 换成另一张（改绑绝不能改到别的样板上）
        final UUID current = fingerprint(held);
        if (current == null || !current.equals(packet.patternId())) {
            send(player, Component.translatable(LANG + "stale"));
            return;
        }
        final ServerLevel level = player.serverLevel();
        final HolderLookup.Provider registries = level.registryAccess();
        final SequencePatternData.AssemblyData assembly = SequencePatternData.readAssembly(held, registries);
        if (assembly == null) {
            send(player, Component.translatable(LANG + "stale"));
            return;
        }
        final List<SequencePatternData.UnitEntry> units = assembly.units();
        if (packet.stepIndex() < 0 || packet.stepIndex() >= units.size()) {
            send(player, Component.translatable(LANG + "no_step"));
            return;
        }
        final SequencePatternData.UnitEntry unit = units.get(packet.stepIndex());
        if (!(level.getBlockEntity(packet.pos()) instanceof SequenceExecutionChamberBlockEntity target)) {
            send(player, Component.translatable(LANG + "bad_target"));
            return;
        }
        // ① 该步必须有配方类型：没有类型就无从判断「这台机器认不认它」
        final String recipeType = unit.recipeType() == null ? "" : unit.recipeType().trim();
        if (recipeType.isEmpty()) {
            send(player, Component.translatable(LANG + "no_recipe_type"));
            return;
        }
        // ② 目标机器必须认这类配方（与服务端既有的候选判定同一口径：类型完全相等）
        final String targetType = target.getRecipeType() == null ? "" : target.getRecipeType();
        if (!recipeType.equals(targetType)) {
            send(player, Component.translatable(LANG + "mismatch", RecipeTypeNames.display(recipeType)));
            return;
        }
        // ③ 该步必须记得「服务配方 + 步序」：没有它就无法在执行舱里认出对应的单元样板
        final String recipe = unit.recipe() == null ? "" : unit.recipe().trim();
        if (recipe.isEmpty() || unit.step() < 0) {
            send(player, Component.translatable(LANG + "no_unit_step"));
            return;
        }
        final Network network = UnitPatternDedupe.networkOf(target);
        if (network == null) {
            send(player, Component.translatable(LANG + "no_network"));
            return;
        }
        // ④ 该步的单元样板现在放在哪些执行舱上（只读扫描；同网络、按坐标升序）
        final List<SequenceExecutionChamberBlockEntity> holders = unitHolders(network, registries, recipe, unit.step());
        if (holders.isEmpty()) {
            // 找不到就不动：宁可保持原样，也绝不写一个「指派到没有该步样板的机器」的悬空绑定
            send(player, Component.translatable(LANG + "no_unit"));
            return;
        }
        // 同一<b>条链</b>的执行舱共用一个逻辑身份（链级属主），已经算「这台机器持有该步」
        final List<SequenceExecutionChamberBlockEntity> chain = target.chainMembers();
        final boolean targetHolds = holders.stream().anyMatch(chain::contains);
        // ⑤ 位置不够就先拒绝（绝不做「先删后放不下」的事）
        final int emptySlot = targetHolds ? -1 : firstEmptyUnitSlot(target);
        if (!targetHolds && emptySlot < 0) {
            send(player, Component.translatable(LANG + "no_slot"));
            return;
        }
        // ⑥ 迁移：把链路之外的重复张收走（目标所在链已持有则只清理别处，绝不新增第二张）。
        // <b>要搬的那张必须在清理之前先取到手</b>：清理会把源槽位置空，之后再回读源槽只会拿到空气，
        // 于是一边把旧机器上的样板删掉、一边什么都没搬过去 —— 那是不可逆的丢样板。
        final ItemStack relocation = targetHolds
            ? ItemStack.EMPTY : firstMatchingHolderStack(holders, registries, recipe, unit.step());
        int moved = 0;
        for (final SequenceExecutionChamberBlockEntity holder : holders) {
            if (chain.contains(holder)) {
                continue; // 目标自己 / 同链成员：本来就算这台机器持有
            }
            for (int slot = 0; slot < holder.unitSlots.getContainerSize(); slot++) {
                if (!isStepUnitPattern(holder.unitSlots.getItem(slot), registries, recipe, unit.step())) {
                    continue;
                }
                holder.unitSlots.setItem(slot, ItemStack.EMPTY);
                holder.setChanged();
                moved++;
                break; // 一台只搬一张
            }
        }
        if (!targetHolds && !relocation.isEmpty()) {
            target.unitSlots.setItem(emptySlot, relocation.copyWithCount(1));
            target.setChanged();
        }
        // ⑦ 原地改写同一件物品（数量不变；候选组 / 流体标记 / 主原料候选全部原样带过去）
        final List<SequencePatternData.UnitEntry> updated = new ArrayList<>(units);
        updated.set(packet.stepIndex(), new SequencePatternData.UnitEntry(
            unit.machine(), unit.count(), unit.input(), unit.crafter(), unit.recipe(), unit.step(),
            target.getBlockPos(), target.getChamberDisplayName(), unit.recipeType(),
            unit.inputFluid(), unit.inputCandidates()));
        final SequencePatternData.AssemblyData data = new SequencePatternData.AssemblyData(
            assembly.ingredient(), assembly.loops(), updated, assembly.results(), assembly.scraps(),
            assembly.ingredientCandidates());
        final ItemStack replacement = held.copyWithCount(held.getCount());
        SequencePatternData.writeAssembly(replacement, data, registries);
        player.setItemInHand(hand, replacement);
        // ⑧ 取证日志（复用既有的 binding 通道：谁把哪一步从哪台改到了哪台）
        RsccAssemblyDebug.event("rebind step=" + packet.stepIndex() + "/" + unit.step()
            + " recipe=" + recipe + " recipeType=" + recipeType
            + " from=" + (unit.machinePos() == null ? "unassigned" : RsccAssemblyDebug.at(unit.machinePos()))
            + " to=" + RsccAssemblyDebug.machine("chamber", target.getBlockPos())
            + " unitMoved=" + moved + " targetHeldAlready=" + targetHolds);
        // ⑨ 反馈 + 权威刷新：界面按新的服务端状态重画（指纹也随之更新，可连续改多步）
        final int stepNumber = packet.stepIndex() + 1;
        final String machineName = target.getChamberDisplayName();
        if (moved > 0) {
            send(player, Component.translatable(LANG + "done.unit", stepNumber, machineName));
        } else {
            send(player, Component.translatable(LANG + "done", stepNumber, machineName));
        }
        openFor(player, hand, target);
    }

    // ==================== 快照构造（S2C 的内容） ====================

    /** 总样板每一步的「步号 / 配方类型 / 当前机器」，供界面列出可改绑的步骤。 */
    private static List<AssemblyPatternRebindOpenPacket.Step> stepEntries(
        final SequencePatternData.AssemblyData assembly) {
        final List<SequencePatternData.UnitEntry> units = assembly.units();
        final List<AssemblyPatternRebindOpenPacket.Step> steps = new ArrayList<>(units.size());
        for (int i = 0; i < units.size(); i++) {
            final SequencePatternData.UnitEntry unit = units.get(i);
            final String name = unit.machineName() == null || unit.machineName().isEmpty()
                ? (unit.machinePos() == null ? "" : unit.machinePos().toShortString())
                : unit.machineName();
            steps.add(new AssemblyPatternRebindOpenPacket.Step(i,
                unit.recipeType() == null ? "" : unit.recipeType(), name,
                unit.machinePos() != null,
                unit.machinePos() == null ? net.minecraft.core.BlockPos.ZERO : unit.machinePos()));
        }
        return steps;
    }

    /**
     * 候选机器（该网络内<b>已配置</b>的执行舱：有名字 + 有配方类型），并<b>按链去重</b>。
     * <p>不在这里按步过滤：一台执行舱可能同时是好几步的候选，界面按每步的配方类型自己筛，
     * 服务端在 {@link #apply} 里仍然逐项复核 —— 客户端永远是「请求」，不是「权威」。</p>
     *
     * <h2>为什么必须按链去重</h2>
     * <p>链上多台＝<b>同一个逻辑执行仓</b>（用户布局：同配方 4 台沿箭头排成一条链 = 扩容），
     * 去重键因此取既有的 {@link SequenceExecutionChamberBlockEntity#chainIdentity()}（链首坐标），
     * 不新造第二套链推导。代表台取「先遇到的那一台」：{@link UnitManagerSources#chambers} 已按坐标升序，
     * 因此结果确定；而名字与配方类型本来就<b>由链首唯一持有并链委托</b>
     * （{@code getChamberName()} / {@code getRecipeType()} 一律读链首），
     * 所以代表台无论是链上哪一台，显示名与类型都完全相同 —— 界面里因此看不出差别，
     * 但候选数从「台数」变成「逻辑执行仓数」，不会再让同一条链重复出现。</p>
     */
    private static List<SyncChamberListPacket.Entry> chamberEntries(@Nullable final Network network) {
        final List<SyncChamberListPacket.Entry> entries = new ArrayList<>();
        final Set<BlockPos> seenChains = new LinkedHashSet<>();
        for (final SequenceExecutionChamberBlockEntity chamber : UnitManagerSources.chambers(network)) {
            final String type = chamber.getRecipeType() == null ? "" : chamber.getRecipeType();
            if (type.isEmpty() || !chamber.isProperlyConfigured()) {
                continue; // 没配好配方类型 / 名字的机器接不了任何样板，不给它当候选
            }
            if (!seenChains.add(chamber.chainIdentity())) {
                continue; // 同一条链的别的成员：同一个逻辑执行仓，合并成一个条目
            }
            entries.add(new SyncChamberListPacket.Entry(
                chamber.getBlockPos(), chamber.getChamberDisplayName(), type));
        }
        return entries;
    }

    // ==================== 只读小工具 ====================

    /** 总样板的「身份指纹」= 组件补丁的 UUID（与 {@link SequenceAssemblyPatternItem#getId} 同源）。 */
    @Nullable
    private static UUID fingerprint(final ItemStack stack) {
        return stack.getItem() instanceof SequenceAssemblyPatternItem pattern ? pattern.getId(stack) : null;
    }

    @Nullable
    private static InteractionHand handOf(final int ordinal) {
        final InteractionHand[] hands = InteractionHand.values();
        return ordinal >= 0 && ordinal < hands.length ? hands[ordinal] : null;
    }

    /** 本网络里放着「该步单元样板」的全部执行舱（按坐标升序；只读，不写任何容器）。 */
    private static List<SequenceExecutionChamberBlockEntity> unitHolders(
        final Network network, final HolderLookup.Provider registries,
        final String recipe, final int step) {
        final List<SequenceExecutionChamberBlockEntity> holders = new ArrayList<>();
        for (final SequenceExecutionChamberBlockEntity chamber : UnitManagerSources.chambers(network)) {
            for (int slot = 0; slot < chamber.unitSlots.getContainerSize(); slot++) {
                if (isStepUnitPattern(chamber.unitSlots.getItem(slot), registries, recipe, step)) {
                    holders.add(chamber);
                    break; // 一台机器算一个持有点（它可能有多张，但属主只有一个）
                }
            }
        }
        return holders;
    }

    /** 该物品是不是「这条配方的这一步」的单元样板（配方大小写不敏感 + 步序相等）。 */
    private static boolean isStepUnitPattern(final ItemStack stack, final HolderLookup.Provider registries,
                                             final String recipe, final int step) {
        if (stack.isEmpty() || !stack.is(RS_Create_Compat.SEQUENCE_UNIT_PATTERN.get())) {
            return false;
        }
        final SequencePatternData.UnitData data = SequencePatternData.readUnit(stack, registries);
        if (data == null || data.step() != step) {
            return false;
        }
        final String other = data.recipe() == null ? "" : data.recipe().trim();
        return !other.isEmpty() && other.equalsIgnoreCase(recipe);
    }

    /** 第一个持有点上那张要搬的单元样板（用于搬进目标机；找不到返回 {@link ItemStack#EMPTY}）。 */
    private static ItemStack firstMatchingHolderStack(final List<SequenceExecutionChamberBlockEntity> holders,
                                                     final HolderLookup.Provider registries,
                                                     final String recipe, final int step) {
        for (final SequenceExecutionChamberBlockEntity holder : holders) {
            for (int slot = 0; slot < holder.unitSlots.getContainerSize(); slot++) {
                final ItemStack stack = holder.unitSlots.getItem(slot);
                if (isStepUnitPattern(stack, registries, recipe, step)) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /** 该执行舱单元样板槽的第一个空格（全满返回 -1）。 */
    private static int firstEmptyUnitSlot(final SequenceExecutionChamberBlockEntity chamber) {
        for (int slot = 0; slot < chamber.unitSlots.getContainerSize(); slot++) {
            if (chamber.unitSlots.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    /** action bar 反馈（服务端权威 → 玩家立刻能看到结果）。 */
    private static void send(final ServerPlayer player, final Component message) {
        player.displayClientMessage(message, true);
    }
}
