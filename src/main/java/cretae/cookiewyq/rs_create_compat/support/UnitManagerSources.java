package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import cretae.cookiewyq.rs_create_compat.block.entity.SequenceExecutionChamberBlockEntity;
import cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity;
import cretae.cookiewyq.rs_create_compat.item.SequenceAssemblyPatternItem;
import cretae.cookiewyq.rs_create_compat.network.SequenceExecutionChamberNetworkNode;
import cretae.cookiewyq.rs_create_compat.network.SequencePatternTerminalNetworkNode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 「单元样板管理舱」的通用数据源与容器包装（服务端权威）。
 *
 * <ul>
 *     <li>{@link #chambers(Network)}：按坐标排序去重地列出网络里的执行舱（单元样板的宿主）。</li>
 *     <li>{@link #terminals(Network)}：列出网络里序列装配样板终端的<b>旧单元样板库</b>（只出不进）。</li>
 *     <li>{@link ChamberUnitContainer}：把某台执行舱的单元样板槽包装成标准 {@code Container}，
 *     把「能放什么」收敛到 {@link SequenceExecutionChamberBlockEntity#acceptsUnit(ItemStack)}
 *     这一条权威判定上（与执行舱界面、手持右键放入完全同源，不存在旁路）。</li>
 *     <li>{@link TerminalLibraryContainer}：把终端里的旧单元样板库包成只出不进的 {@code Container}。</li>
 *     <li>{@link #hiddenMasterPatternSlots(Container)}：算出该容器里「不该出现在管理舱界面上」的槽位下标
 *     （按<b>物品类型</b>：序列装配总样板）。由服务端随开界面一起下发，两端隐藏<b>同一批下标</b>
 *     —— 容器、槽位下标与已存数据一字不动，只把这几格挪出可见网格。</li>
 * </ul>
 *
 * <p><b>本类不再提供「序列装配样板库（总样板）」视图</b>：总样板由 RS 原版「自动合成管理舱」管理，
 * 单元样板管理舱只负责单元样板。</p>
 */
public final class UnitManagerSources {
    private UnitManagerSources() {
    }

    /** 网络内全部执行舱（按坐标排序、去重；无网络时为空列表）。 */
    public static List<SequenceExecutionChamberBlockEntity> chambers(@Nullable final Network network) {
        final List<SequenceExecutionChamberBlockEntity> result = new ArrayList<>();
        if (network == null) {
            return result;
        }
        for (final NetworkNodeContainer container : containersOf(network)) {
            if (container.getNode() instanceof SequenceExecutionChamberNetworkNode node
                && node.getBlockEntity() != null) {
                result.add(node.getBlockEntity());
            }
        }
        return dedupeByPos(result, SequenceExecutionChamberBlockEntity::getBlockPos);
    }

    /**
     * 网络内全部<b>序列装配样板终端</b>（按坐标排序、去重）。
     * <p>终端的「单元样板库」自 v7 起不再由终端界面承载（那里的交互区已整体删除），
     * 但库内容仍原样保存在终端 NBT 里；这里把它作为管理舱的一个只出不进分组暴露出来，
     * 玩家可取出并搬进执行舱 —— 这是「旧数据不丢」的落地方式。</p>
     */
    public static List<SequencePatternTerminalBlockEntity> terminals(@Nullable final Network network) {
        final List<SequencePatternTerminalBlockEntity> result = new ArrayList<>();
        if (network == null) {
            return result;
        }
        for (final NetworkNodeContainer container : containersOf(network)) {
            if (container.getNode() instanceof SequencePatternTerminalNetworkNode node
                && node.getBlockEntity() != null) {
                result.add(node.getBlockEntity());
            }
        }
        return dedupeByPos(result, SequencePatternTerminalBlockEntity::getBlockPos);
    }

    /**
     * 该容器里必须从「单元样板管理舱」界面上<b>隐藏</b>的槽位下标（按物品类型：序列装配总样板）。
     *
     * <p><b>为什么要由服务端算、随界面一起下发</b>：客户端的镜像容器必须等原版槽位同步包到达后才有内容，
     * 而它是在<b>菜单构造 / 界面 init</b> 那一刻就按「本组多少格」重建槽位的 —— 那一刻容器还是空的。
     * 因此「客户端自己按内容过滤」在首帧必然失效（旧存档里的总样板照常显示，正是上一版没生效的根因）；
     * 改由服务端在开界面时扫一遍真实容器、把下标随 {@code UnitPatternManagerData} 下发，
     * 客户端一开始就知道该藏哪几格，且<b>两端隐藏的是同一批下标</b>。</p>
     *
     * <p><b>为什么不改容器、不抽掉槽位</b>：隐藏只改变<b>位置与可见性</b>，每个下标照旧各建一个
     * {@code Slot}（挪到裁剪区上方），因此原版槽位同步的两端下标一一对应；容器内容、
     * 分组结构与已存样板数据一律不动（「看不见」≠「删掉」）。</p>
     *
     * <p>顺序确定（下标升序、去重），因此同一批容器在两处调用（服务端菜单 / 下发数据）得到的结果完全一致。</p>
     */
    public static List<Integer> hiddenMasterPatternSlots(final Container container) {
        final List<Integer> hidden = new ArrayList<>();
        if (container == null) {
            return hidden;
        }
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (SequenceAssemblyPatternItem.isAssemblyPattern(container.getItem(i))) {
                hidden.add(i);
            }
        }
        return hidden;
    }

    private static java.util.Collection<NetworkNodeContainer> containersOf(final Network network) {
        final GraphNetworkComponent graph = network.getComponent(GraphNetworkComponent.class);
        return graph == null ? List.of() : graph.getContainers();
    }

    private static <T> List<T> dedupeByPos(final List<T> input, final java.util.function.Function<T, BlockPos> pos) {
        input.sort(Comparator.comparingLong(value -> pos.apply(value).asLong()));
        final List<T> unique = new ArrayList<>(input.size());
        long last = Long.MIN_VALUE;
        for (final T value : input) {
            final long key = pos.apply(value).asLong();
            if (key == last) {
                continue;
            }
            last = key;
            unique.add(value);
        }
        return unique;
    }

    // ==================== 合并分组（「成链的执行舱 = 一组」，照 RS 自动合成管理器） ====================

    /**
     * 单元样板管理舱的<b>一个分组</b>：一组执行舱（同一条链 / 同一个相邻集群）+ 该组的合并容器。
     *
     * @param members      组成员（按坐标升序；已滤掉「未配置完成」的执行舱）
     * @param container    组成员单元槽的<b>合并视图</b>（写入会落回正确的那个舱，见
     *                     {@link ClusterUnitContainer}）
     * @param name         组标题（代表名；多台时已带成员数后缀）
     * @param recipeType   组配方类型（成员不一致时为空串 = 界面显示「未知」）
     */
    public record ChamberGroup(List<SequenceExecutionChamberBlockEntity> members,
                               Container container, String name, String recipeType) {
        /** 组内执行舱台数（界面用「N 台」标注，因此合并后仍能看出里面有谁 / 有几台）。 */
        public int size() {
            return members.size();
        }
    }

    /**
     * 把网络里的执行舱按「同一条链 / 同一个相邻集群」<b>合并成分组</b>（用户要求：像 RS 原版自动合成
     * 管理器那样，多个合成器连在一起就合并成一个条目）。
     *
     * <p><b>合并口径</b>：链（{@link SequenceExecutionChamberBlockEntity#chainMembers()}，照 RS
     * {@code MAX_CHAINED_AUTOCRAFTERS} 的朝向链）与相邻机器集群（{@link RsccMachineCluster#clusterMembers}，
     * 它们本来就共用同一份内部存储 = 物理上就是一台机器）取<b>并集并求传递闭包</b>，
     * 因此无论玩家把执行舱「串成链」还是「贴成一排」，都只会看到<b>一个</b>条目，不会被拆成两组。</p>
     *
     * <p><b>未配置的不显示</b>：{@link SequenceExecutionChamberBlockEntity#isProperlyConfigured()} 为假
     * （名字或配方类型为空）的执行舱<b>直接滤掉</b>，不进入任何分组 —— 用户要求原话：
     * 「没有正确配置它的名字和配方类型的话，它不应该显示到单元样板管理器中」。</p>
     *
     * <p>顺序确定：组按「组内最小坐标」升序，组内成员按坐标升序，因此两端（服务端 / 客户端）
     * 看到的分组结构永远一致。</p>
     */
    public static List<ChamberGroup> chamberGroups(@Nullable final Network network) {
        final List<SequenceExecutionChamberBlockEntity> chambers = chambers(network);
        // 只保留「配置完成」的执行舱（未配置的一律不进任何分组）
        final List<SequenceExecutionChamberBlockEntity> visible = new ArrayList<>(chambers.size());
        for (final SequenceExecutionChamberBlockEntity chamber : chambers) {
            if (chamber.isProperlyConfigured()) {
                visible.add(chamber);
            }
        }
        if (visible.isEmpty()) {
            return List.of();
        }
        // 并查集：链 ∪ 相邻集群（传递闭包），因此两种「成组」方式都能合并到一个组里
        final java.util.Map<Long, Long> parent = new java.util.HashMap<>();
        for (final SequenceExecutionChamberBlockEntity chamber : visible) {
            parent.put(chamber.getBlockPos().asLong(), chamber.getBlockPos().asLong());
        }
        for (final SequenceExecutionChamberBlockEntity chamber : visible) {
            for (final SequenceExecutionChamberBlockEntity mate : related(chamber)) {
                final Long mateKey = parent.get(mate.getBlockPos().asLong());
                if (mateKey != null) {
                    union(parent, chamber.getBlockPos().asLong(), mateKey);
                }
            }
        }
        final java.util.Map<Long, List<SequenceExecutionChamberBlockEntity>> byRoot =
            new java.util.LinkedHashMap<>();
        for (final SequenceExecutionChamberBlockEntity chamber : visible) {
            byRoot.computeIfAbsent(find(parent, chamber.getBlockPos().asLong()), key -> new ArrayList<>())
                .add(chamber);
        }
        final List<ChamberGroup> groups = new ArrayList<>(byRoot.size());
        for (final List<SequenceExecutionChamberBlockEntity> members : byRoot.values()) {
            members.sort(Comparator.comparingLong(member -> member.getBlockPos().asLong()));
            final SequenceExecutionChamberBlockEntity master = members.get(0);
            final String base = master.getChamberDisplayName();
            final String title = members.size() <= 1 ? base : base + " ×" + members.size();
            final String recipeType = commonRecipeType(members);
            final Container container = members.size() == 1
                ? new ChamberUnitContainer(members.get(0))
                : new ClusterUnitContainer(members);
            groups.add(new ChamberGroup(List.copyOf(members), container, title, recipeType));
        }
        groups.sort(Comparator.comparingLong(group -> group.members().get(0).getBlockPos().asLong()));
        return groups;
    }

    /** 与本台「同一链或同一相邻集群」的其它执行舱（自身不在结果里；只读）。 */
    private static List<SequenceExecutionChamberBlockEntity> related(
        final SequenceExecutionChamberBlockEntity chamber) {
        final List<SequenceExecutionChamberBlockEntity> result = new ArrayList<>(4);
        for (final SequenceExecutionChamberBlockEntity member : chamber.chainMembers()) {
            if (member != chamber) {
                result.add(member);
            }
        }
        final net.minecraft.world.level.Level level = chamber.getLevel();
        if (level != null) {
            for (final BlockPos pos : RsccMachineCluster.clusterMembers(level, chamber.getBlockPos())) {
                if (pos.equals(chamber.getBlockPos())) {
                    continue;
                }
                if (level.getBlockEntity(pos) instanceof final SequenceExecutionChamberBlockEntity mate
                    && mate != chamber) {
                    result.add(mate);
                }
            }
        }
        return result;
    }

    /** 组内成员共同持有的配方类型；不一致（或都为空）时返回空串（界面显示「未知」，绝不猜）。 */
    private static String commonRecipeType(final List<SequenceExecutionChamberBlockEntity> members) {
        String common = null;
        for (final SequenceExecutionChamberBlockEntity member : members) {
            final String type = member.getRecipeType() == null ? "" : member.getRecipeType();
            if (type.isEmpty()) {
                return "";
            }
            if (common == null) {
                common = type;
            } else if (!common.equals(type)) {
                return "";
            }
        }
        return common == null ? "" : common;
    }

    private static long find(final java.util.Map<Long, Long> parent, final long key) {
        Long current = parent.get(key);
        if (current == null || current == key) {
            return key;
        }
        final long root = find(parent, current);
        parent.put(key, root);
        return root;
    }

    private static void union(final java.util.Map<Long, Long> parent, final long a, final long b) {
        final long rootA = find(parent, a);
        final long rootB = find(parent, b);
        if (rootA != rootB) {
            parent.put(Math.max(rootA, rootB), Math.min(rootA, rootB)); // 以较小坐标为根：结果确定
        }
    }

    // ==================== 执行舱单元样板容器（真实读写，判定唯一） ====================

    /** 执行舱单元样板槽的标准容器包装：可放 / 可取的权威判定全部落在执行舱自身。 */
    public static final class ChamberUnitContainer implements Container {
        private final SequenceExecutionChamberBlockEntity chamber;

        public ChamberUnitContainer(final SequenceExecutionChamberBlockEntity chamber) {
            this.chamber = chamber;
        }

        @Override
        public int getContainerSize() {
            return chamber.unitSlots.getContainerSize();
        }

        @Override
        public boolean isEmpty() {
            return chamber.unitSlots.isEmpty();
        }

        @Override
        public ItemStack getItem(final int index) {
            return chamber.unitSlots.getItem(index);
        }

        @Override
        public ItemStack removeItem(final int index, final int amount) {
            final ItemStack removed = chamber.unitSlots.removeItem(index, amount);
            if (!removed.isEmpty()) {
                chamber.setChanged();
            }
            return removed;
        }

        @Override
        public ItemStack removeItemNoUpdate(final int index) {
            final ItemStack removed = chamber.unitSlots.removeItemNoUpdate(index);
            if (!removed.isEmpty()) {
                chamber.setChanged();
            }
            return removed;
        }

        @Override
        public void setItem(final int index, final ItemStack stack) {
            chamber.unitSlots.setItem(index, stack);
            chamber.setChanged();
        }

        @Override
        public void setChanged() {
            chamber.setChanged();
        }

        /** 刻意为空操作：通用「清空容器」路径（玩家死亡掉落等）不得抹掉执行舱里的样板。 */
        @Override
        public void clearContent() {
        }

        @Override
        public boolean stillValid(final Player player) {
            return true;
        }

        /** 单元样板一格一张。 */
        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean canPlaceItem(final int index, final ItemStack stack) {
            return chamber.acceptsUnit(stack);
        }
    }

    // ==================== 合并视图：一组执行舱的单元槽（写入落回正确的那个舱） ====================

    /**
     * 一组执行舱（同一条链 / 同一个相邻集群）单元槽的<b>合并视图</b>（用户要求：
     * 「成链之后在单元样板管理器中合并显示，就像精致存储原版那样」）。
     *
     * <p><b>槽位下标 → 归属舱的映射在构造时一次算好</b>：第 {@code i} 个槽属于
     * {@code members.get(k)} 的第 {@code local} 个槽。因此：</p>
     * <ul>
     *     <li>读 / 写 / 取出<b>都落回真正的那个执行舱</b>（槽位只是个视图，绝不会错位写进别的舱；
     *     这是用户点名要求的「合并视图的写入必须落回正确的那个舱」）；</li>
     *     <li>「能放什么」仍然只由<b>归属那一台</b>的 {@link SequenceExecutionChamberBlockEntity#acceptsUnit}
     *     判定（与执行舱界面、手持右键放入完全同源，不存在旁路）；</li>
     *     <li>{@link #setChanged()} 会标记<b>全部</b>成员落盘，因此合并视图不会出现「改了没存」的成员。</li>
     * </ul>
     * <p>只做视图合并，不复制、不搬迁任何样板：同一份 {@code ItemStack} 始终只存在于它原本所在的那台舱里。</p>
     */
    public static final class ClusterUnitContainer implements Container {
        /** 组成员（按坐标升序，与组标题一致）。 */
        private final List<SequenceExecutionChamberBlockEntity> members;
        /** 合并下标 → 「成员下标」。长度为总槽数；{@code owner[i]} 即第 i 个槽属于哪一台。 */
        private final int[] owner;
        /** 合并下标 → 该成员内的本地槽下标。 */
        private final int[] local;

        public ClusterUnitContainer(final List<SequenceExecutionChamberBlockEntity> members) {
            this.members = List.copyOf(members);
            int total = 0;
            for (final SequenceExecutionChamberBlockEntity member : this.members) {
                total += member.unitSlots.getContainerSize();
            }
            this.owner = new int[total];
            this.local = new int[total];
            int index = 0;
            for (int m = 0; m < this.members.size(); m++) {
                final int size = this.members.get(m).unitSlots.getContainerSize();
                for (int s = 0; s < size; s++) {
                    owner[index] = m;
                    local[index] = s;
                    index++;
                }
            }
        }

        /** 该下标归属的执行舱（越界返回 {@code null}，由调用方按「空槽」处理）。 */
        @Nullable
        private SequenceExecutionChamberBlockEntity memberOf(final int index) {
            if (index < 0 || index >= owner.length) {
                return null;
            }
            return members.get(owner[index]);
        }

        /** 该下标在归属舱里的本地槽位（越界返回 {@code -1}）。 */
        private int localOf(final int index) {
            return index < 0 || index >= local.length ? -1 : local[index];
        }

        @Override
        public int getContainerSize() {
            return owner.length;
        }

        @Override
        public boolean isEmpty() {
            for (final SequenceExecutionChamberBlockEntity member : members) {
                if (!member.unitSlots.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(final int index) {
            final SequenceExecutionChamberBlockEntity member = memberOf(index);
            return member == null ? ItemStack.EMPTY : member.unitSlots.getItem(localOf(index));
        }

        @Override
        public ItemStack removeItem(final int index, final int amount) {
            final SequenceExecutionChamberBlockEntity member = memberOf(index);
            if (member == null) {
                return ItemStack.EMPTY;
            }
            final ItemStack removed = member.unitSlots.removeItem(localOf(index), amount);
            if (!removed.isEmpty()) {
                member.setChanged();
            }
            return removed;
        }

        @Override
        public ItemStack removeItemNoUpdate(final int index) {
            final SequenceExecutionChamberBlockEntity member = memberOf(index);
            if (member == null) {
                return ItemStack.EMPTY;
            }
            final ItemStack removed = member.unitSlots.removeItemNoUpdate(localOf(index));
            if (!removed.isEmpty()) {
                member.setChanged();
            }
            return removed;
        }

        @Override
        public void setItem(final int index, final ItemStack stack) {
            final SequenceExecutionChamberBlockEntity member = memberOf(index);
            if (member == null) {
                return; // 越界（理论上不可达）：丢弃这次写入，绝不越界写进别的舱
            }
            member.unitSlots.setItem(localOf(index), stack);
            member.setChanged();
        }

        @Override
        public void setChanged() {
            for (final SequenceExecutionChamberBlockEntity member : members) {
                member.setChanged();
            }
        }

        /** 刻意为空操作：通用「清空容器」路径（玩家死亡掉落等）不得抹掉执行舱里的样板。 */
        @Override
        public void clearContent() {
        }

        @Override
        public boolean stillValid(final Player player) {
            return true;
        }

        /** 单元样板一格一张。 */
        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean canPlaceItem(final int index, final ItemStack stack) {
            final SequenceExecutionChamberBlockEntity member = memberOf(index);
            return member != null && member.acceptsUnit(stack);
        }
    }

    // ==================== 终端「单元样板库」容器（只出不进，用于抢救旧数据） ====================
    /**
     * 把某台序列装配样板终端自带的单元样板库包成标准 {@code Container}（<b>只出不进</b>）。
     *
     * <p><b>为什么需要它</b>：v7 起终端界面不再承载单元样板（那块 UI 已整体删除），但旧存档里
     * 终端 NBT 的 {@code UnitLibrary} 仍原样保留。把它挂成管理舱的一个分组，玩家就能把旧样板
     * 取出并搬进执行舱，<b>不会凭空消失</b>。</p>
     *
     * <p><b>尺寸固定</b>：容量在构造时按「最后一个非空槽 + 1」算好并<b>冻结</b>（两端槽位数必须一致，
     * 否则原版槽位同步会错位）。库只增不改，因此冻结的容量不会遮掉已有样板；此容器本身
     * {@link #canPlaceItem} 恒 false，界面无法让它增长。</p>
     */
    public static final class TerminalLibraryContainer implements Container {
        private final SequencePatternTerminalBlockEntity terminal;
        private final int size;

        public TerminalLibraryContainer(final SequencePatternTerminalBlockEntity terminal) {
            this.terminal = terminal;
            int highest = -1;
            final int slots = terminal.unitLibrary.getSlots();
            for (int i = 0; i < slots; i++) {
                if (!terminal.unitLibrary.getStackInSlot(i).isEmpty()) {
                    highest = i;
                }
            }
            this.size = highest + 1;
        }

        @Override
        public int getContainerSize() {
            return size;
        }

        @Override
        public boolean isEmpty() {
            return size <= 0;
        }

        @Override
        public ItemStack getItem(final int index) {
            if (index < 0 || index >= size) {
                return ItemStack.EMPTY;
            }
            return terminal.unitLibrary.getStackInSlot(index);
        }

        @Override
        public ItemStack removeItem(final int index, final int amount) {
            if (index < 0 || index >= size) {
                return ItemStack.EMPTY;
            }
            final ItemStack removed = terminal.unitLibrary.extractItem(index, amount, false);
            if (!removed.isEmpty()) {
                terminal.setChanged();
            }
            return removed;
        }

        @Override
        public ItemStack removeItemNoUpdate(final int index) {
            return removeItem(index, Integer.MAX_VALUE);
        }

        /** 空操作：终端库只出不进（旧数据的抢救出口，不接收任何写入）。 */
        @Override
        public void setItem(final int index, final ItemStack stack) {
        }

        @Override
        public void setChanged() {
            terminal.setChanged();
        }

        /** 刻意为空操作：通用「清空容器」路径不得抹掉终端里的旧样板。 */
        @Override
        public void clearContent() {
        }

        @Override
        public boolean stillValid(final Player player) {
            return true;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean canPlaceItem(final int index, final ItemStack stack) {
            return false;
        }
    }
}
