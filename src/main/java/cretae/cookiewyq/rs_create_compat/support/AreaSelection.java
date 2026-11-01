package cretae.cookiewyq.rs_create_compat.support;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 「区域选定器」的<b>全部数据</b>（一个物品一份，存在注册版数据组件里）。
 *
 * <h2>为什么走数据组件（而不是 {@code CUSTOM_DATA} 塞 NBT）</h2>
 * <p>照 {@code RAW_MATERIAL}（{@code support/SequenceMaterialGuard.RawMaterialMark}）的既有先例：
 * 注册版数据组件同时提供 {@code persistent(Codec)}（存档 / 掉落物保留）与
 * {@code networkSynchronized(StreamCodec)}（客户端要画框、GUI 要显示列表）。
 * 只走 {@code CUSTOM_DATA} 的物品在 1.21.1 里没有网络同步契约，界面拿到的是空数据。</p>
 *
 * <h2>维度<b>绑定在选定器上</b>，不绑定在区域上</h2>
 * <p>区域是世界坐标的固定盒子，主世界的 (100,64,100) 与下界的同名坐标是两回事。
 * 一个选定器只服务一个维度：{@link #dimension()} 为 {@code null} 表示「还没绑定」，
 * 第一次落笔（在某个维度里提交第一个区域）由服务端绑定；之后在别的维度里提交一律被拒
 * （界面提示「跨维度」），高级缓存仓也<b>忽略</b>维度不符的选定器（等价于没装）。</p>
 *
 * <h2>上限</h2>
 * <p>{@link #sanitize(AreaSelection)} 按 {@code Config.areaSelectorMaxAreas} /
 * {@code Config.areaSelectorMaxVolume} 截断。</p>
 * <p><b>它只用于「消费侧」（读档 / 扫描 / 状态显示），绝不在写回路径上调用</b> —— 这是本轮的
 * 一条数据安全纪律：配置是<b>可改小</b>的，而 {@code Config} 明确承诺「改小后旧存档里超出的区域
 * 不会丢：物品上的数据保留，只是不参与扫描（放回大值即恢复）」。若写回时也 sanitize，
 * 玩家只是点选一行（几何没变）就会把超限的历史区域永久切掉。
 * 因此：</p>
 * <ul>
 *     <li>{@code AreaSelectorEditPacket} 写回的是「本次动作应用到不可变记录」的结果，不 sanitize；</li>
 *     <li>高级归流缓存仓的 {@code resolveSelection} / {@code getAreaStatus} 再 sanitize，
 *     使超限部分<b>不参与扫描</b>且界面如实显示；</li>
 *     <li>上限校验发生在 {@code ADD / REPLACE} 时对<b>新几何</b>的校验（{@link Area#invalidReason(long)}）。</li>
 * </ul>
 */
public record AreaSelection(@Nullable ResourceLocation dimension, List<Area> areas, int selectedIndex) {
    /** 绝对硬上限（配置不得越过）：防一个恶意包塞 100 万个区域。 */
    public static final int ABSOLUTE_MAX_AREAS = 64;

    public AreaSelection {
        areas = areas == null ? List.of() : List.copyOf(areas);
        selectedIndex = areas.isEmpty() ? -1 : Math.max(-1, Math.min(selectedIndex, areas.size() - 1));
    }

    /** 空数据（维度未绑定）。 */
    public static final AreaSelection EMPTY = new AreaSelection(null, List.of(), -1);

    private static final StreamCodec<ByteBuf, ResourceLocation> DIMENSION_CODEC =
        ByteBufCodecs.STRING_UTF8.map(
            text -> text == null || text.isEmpty() ? null : ResourceLocation.tryParse(text),
            id -> id == null ? "" : id.toString()
        );

    public static final Codec<AreaSelection> CODEC = RecordCodecBuilder.create(builder -> builder.group(
        ResourceLocation.CODEC.optionalFieldOf("dimension")
            .forGetter(selection -> Optional.ofNullable(selection.dimension())),
        Area.CODEC.listOf().fieldOf("areas").forGetter(AreaSelection::areas),
        Codec.INT.optionalFieldOf("selected", -1).forGetter(AreaSelection::selectedIndex)
    ).apply(builder, (dimension, areas, selected) ->
        new AreaSelection(dimension.orElse(null), areas, selected)));

    public static final StreamCodec<ByteBuf, AreaSelection> STREAM_CODEC = StreamCodec.composite(
        DIMENSION_CODEC, AreaSelection::dimension,
        Area.STREAM_CODEC.apply(ByteBufCodecs.list()), AreaSelection::areas,
        ByteBufCodecs.VAR_INT, AreaSelection::selectedIndex,
        AreaSelection::new
    );

    // ==================== 物品 ↔ 数据 ====================

    /** 读物品上的区域数据；没有组件时返回 {@link #EMPTY}（不返回 null，调用方少一层判空）。 */
    public static AreaSelection get(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return EMPTY;
        }
        final AreaSelection selection = stack.get(RS_Create_Compat.AREA_SELECTION.get());
        return selection == null ? EMPTY : selection;
    }

    /** 写回物品（服务端权威；客户端只做「乐观显示」时也会写自己那份副本，服务端会覆盖回来）。 */
    public static void set(final ItemStack stack, final AreaSelection selection) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        stack.set(RS_Create_Compat.AREA_SELECTION.get(), selection == null ? EMPTY : selection);
    }

    /** 当前配置允许的区域个数（夹到绝对硬上限）。 */
    public static int maxAreas() {
        return Math.max(1, Math.min(ABSOLUTE_MAX_AREAS, Config.areaSelectorMaxAreas));
    }

    /** 当前配置允许的单区域体积（格）。 */
    public static long maxVolume() {
        return Math.max(1L, Config.areaSelectorMaxVolume);
    }

    /**
     * 按配置截断：丢掉超体积 / 超单边的区域，最多保留 {@link #maxAreas()} 个，
     * 并把 {@code selectedIndex} 收敛到合法范围。顺序保持（玩家看到的列表不会跳）。
     *
     * <p><b>只在消费侧调用</b>（高级归流缓存仓解析扫描范围 / 计算界面状态）。
     * <b>写回物品时不许调用</b>：截断结果一旦写回，配置调小期间的历史区域就永久消失了
     * （见类注释「上限」）。</p>
     */
    public static AreaSelection sanitize(@Nullable final AreaSelection selection) {
        if (selection == null) {
            return EMPTY;
        }
        final long maxVolume = maxVolume();
        final List<Area> kept = new ArrayList<>(Math.min(selection.areas().size(), maxAreas()));
        for (final Area area : selection.areas()) {
            if (kept.size() >= maxAreas()) {
                break;
            }
            if (area == null || area.invalidReason(maxVolume) != null) {
                continue;
            }
            kept.add(area);
        }
        return new AreaSelection(selection.dimension(), kept,
            kept.isEmpty() ? -1 : Math.min(selection.selectedIndex(), kept.size() - 1));
    }

    // ==================== 查询 / 编辑（不可变） ====================

    public boolean isEmpty() {
        return areas.isEmpty();
    }

    public int areaCount() {
        return areas.size();
    }

    public long totalVolume() {
        long total = 0L;
        for (final Area area : areas) {
            total += area.volume();
            if (total < 0L) {
                return Long.MAX_VALUE;
            }
        }
        return total;
    }

    /** 当前选中的区域（无选中返回 null）。 */
    @Nullable
    public Area selected() {
        return selectedIndex >= 0 && selectedIndex < areas.size() ? areas.get(selectedIndex) : null;
    }

    public AreaSelection withDimension(@Nullable final ResourceLocation newDimension) {
        return new AreaSelection(newDimension, areas, selectedIndex);
    }

    public AreaSelection withAreas(final List<Area> newAreas) {
        return new AreaSelection(dimension, newAreas, selectedIndex);
    }

    /** 替换某个下标（越界返回自身）。 */
    public AreaSelection replace(final int index, final Area area) {
        if (index < 0 || index >= areas.size() || area == null) {
            return this;
        }
        final List<Area> next = new ArrayList<>(areas);
        next.set(index, area);
        return new AreaSelection(dimension, next, selectedIndex);
    }

    /** 追加一个区域并自动选中它（调用方保证未超上限）。 */
    public AreaSelection append(final Area area) {
        if (area == null) {
            return this;
        }
        final List<Area> next = new ArrayList<>(areas);
        next.add(area);
        return new AreaSelection(dimension, next, next.size() - 1);
    }

    /** 删除某个下标（越界返回自身）。 */
    public AreaSelection remove(final int index) {
        if (index < 0 || index >= areas.size()) {
            return this;
        }
        final List<Area> next = new ArrayList<>(areas);
        next.remove(index);
        final int selected = next.isEmpty() ? -1 : Math.min(index, next.size() - 1);
        return new AreaSelection(dimension, next, selected);
    }

    /** 选中某个下标（{@code -1} = 取消选中）。 */
    public AreaSelection select(final int index) {
        final int value = areas.isEmpty() ? -1 : Math.max(-1, Math.min(index, areas.size() - 1));
        return new AreaSelection(dimension, areas, value);
    }

    /** 该维度是否可以在这台机器上生效（未绑定视为可绑定；绑定后必须同维度）。 */
    public boolean matchesDimension(@Nullable final ResourceLocation current) {
        return dimension == null || dimension.equals(current);
    }

    // ==================== 第 96 轮：去重 / 复制 / 重命名 ====================

    /**
     * 与 {@code candidate} <b>几何完全相同</b>的区域下标（{@code -1} = 不重复）。
     * <p>判据刻意只有「六个坐标」（{@link Area#sameGeometry(Area)}）：玩家原话是
     * 「标记两个一样的地方它还是会这样子显示」，所以「同一块地」就是重复 —— 名字 / 匹配规则
     * 都不参与判等（否则改个名字就能绕过拦截）。维度由调用方负责（本记录只有一个维度字段）。</p>
     *
     * @param excludeIndex 要不要跳过某一个下标（{@code REPLACE} 时「自己跟自己」不算重复）
     */
    public int indexOfGeometry(@Nullable final Area candidate, final int excludeIndex) {
        if (candidate == null) {
            return -1;
        }
        for (int i = 0; i < areas.size(); i++) {
            if (i != excludeIndex && candidate.sameGeometry(areas.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 几何是否已存在（{@code excludeIndex} 见 {@link #indexOfGeometry(Area, int)}）。 */
    public boolean hasGeometry(@Nullable final Area candidate, final int excludeIndex) {
        return indexOfGeometry(candidate, excludeIndex) >= 0;
    }

    /**
     * 按几何去重后的区域列表（世界描边 / 「显示全部」用）。
     * <p>去重拦截在 {@code ADD / REPLACE / COPY} 时就做了，正常情况下这里没有重复项；但<b>旧存档</b>
     * 里可能已经有重复（本轮之前没有拦截），所以绘制侧仍然自己再兜一层 —— 否则会「同一个框叠画两份」，
     * 视觉上看起来就是加粗 / 闪烁，正是玩家投诉的那个现象。顺序与列表保持一致（第一份为准）。</p>
     */
    public List<Area> distinctAreas() {
        final List<Area> out = new ArrayList<>(areas.size());
        for (final Area area : areas) {
            if (area != null && indexOfGeometryIn(out, area) < 0) {
                out.add(area);
            }
        }
        return List.copyOf(out);
    }

    private static int indexOfGeometryIn(final List<Area> list, final Area area) {
        for (int i = 0; i < list.size(); i++) {
            if (area.sameGeometry(list.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 「复制」第 {@code index} 个区域：同尺寸、整体沿 +X 平移，直到<b>不撞</b>已有区域为止。
     * <p>为什么不原地复制：原地复制出来的几何与原区域完全相同，会被去重立刻拒掉（那才是正确的行为）；
     * 玩家点「复制」的意图是「再要一块一样的、挪开一点」，所以平移 +1 格是最小惊讶的落点。
     * 平移最多试 {@link Area#MAX_SIDE} 次（保证一定在单边上限内）；全都撞上 ⇒ 返回 {@code null}
     * （调用方提示「没有可用的位置」）。</p>
     *
     * @return 新集合（选中项 = 新区域）；无法复制时 {@code null}
     */
    @Nullable
    public AreaSelection duplicate(final int index) {
        if (index < 0 || index >= areas.size() || areas.size() >= ABSOLUTE_MAX_AREAS) {
            return null;
        }
        Area candidate = areas.get(index).shifted();
        for (int attempt = 0; attempt < Area.MAX_SIDE; attempt++) {
            if (!hasGeometry(candidate, -1) && candidate.invalidReason(maxVolume()) == null) {
                return append(candidate);
            }
            candidate = candidate.shifted();
        }
        return null;
    }

    /** 「重命名」第 {@code index} 个区域（名字不参与判等，因此永远不冲突；空白 = 清除名字）。 */
    public AreaSelection rename(final int index, @Nullable final String name) {
        if (index < 0 || index >= areas.size()) {
            return this;
        }
        return replace(index, areas.get(index).withName(name));
    }
}
