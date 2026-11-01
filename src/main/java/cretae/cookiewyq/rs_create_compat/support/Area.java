package cretae.cookiewyq.rs_create_compat.support;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * 一个<b>闭区间的世界方块区域</b>（含首含尾，绝对坐标）。
 *
 * <h2>为什么是绝对坐标 + 六个数（不是「相对机器的偏移 + 尺寸」）</h2>
 * <p>玩家心里的区域是「那片地」，不是「机器的属性」：把同一张选定器插到另一台高级缓存仓上，
 * 它必须仍然指向同一个地方（{@code docs/AREA_SELECTOR_AND_CHARGE_RANGE.md} §4.5 的结论）。
 * 记录里<b>不含维度</b>：维度绑定在 {@link AreaSelection} 上（一个选定器 = 一份区域集合 + 一个维度），
 * 这样「跨维度」只需要比较一次，不必给每个区域都存一份维度。</p>
 *
 * <h2>归一化</h2>
 * <p>紧凑构造器强制 {@code min <= max}（三点手势 / 数字输入框无论先填哪个角都合法），
 * 因此 {@link #volume()} 恒为正、{@link #contains(BlockPos)} 与 {@link #toAabb()} 不需要再判断方向。</p>
 *
 * <h2>名字（第 96 轮新增，<b>不参与判等</b>）</h2>
 * <p>玩家要求「可编辑 … 复制 / 删除 / <b>重命名</b>」，因此每个区域带一个可选名字
 * （≤{@link #MAX_NAME_LENGTH} 字符，只用于列表显示与识别）。<b>名字不参与去重</b>：
 * {@link #sameGeometry(Area)} 只比六个坐标 ——「同维度 + 同坐标」就是同一个区域，
 * 改个名字不能绕过「已存在相同区域」的拦截。存档编解码走 {@code optionalFieldOf("name")}、
 * 网络编码用空串表示无名字，因此<b>旧存档 / 旧网络包</b>读出来就是「无名字」，不需要任何迁移。</p>
 *
 * <h2>上限口径（由 Config 决定，不在这里写死）</h2>
 * <p>{@link #MAX_SIDE} 是<b>单边硬上限</b>（防止 1×1×1,000,000 这种长条把实体 AABB 撑到渲染距离之外）；
 * 区域个数与单区域体积走 {@code Config.areaSelectorMaxAreas} / {@code Config.areaSelectorMaxVolume}
 * （用户拍板：两个 int 可配置）。</p>
 */
public record Area(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                   @Nullable String name) {
    /** 单边硬上限（格）。体积上限由 Config 给；这一条只拦「极细长条」。 */
    public static final int MAX_SIDE = 128;
    /** 世界坐标合理范围（原版世界边界 3e7）。超出即视为非法区域，绝不写入数据组件。 */
    public static final int WORLD_LIMIT = 30_000_000;
    /** 名字最大长度（字符）。超长截断而不是拒绝：界面输入框已经限长，这里只是网络边界的保险。 */
    public static final int MAX_NAME_LENGTH = 24;

    /** 六字段构造（既有调用点全部走这里；名字为 {@code null} = 没有名字）。 */
    public Area(final int minX, final int minY, final int minZ,
                final int maxX, final int maxY, final int maxZ) {
        this(minX, minY, minZ, maxX, maxY, maxZ, null);
    }

    public Area {
        if (minX > maxX) {
            final int swap = minX;
            minX = maxX;
            maxX = swap;
        }
        if (minY > maxY) {
            final int swap = minY;
            minY = maxY;
            maxY = swap;
        }
        if (minZ > maxZ) {
            final int swap = minZ;
            minZ = maxZ;
            maxZ = swap;
        }
        // 名字归一化：空白 ⇒ null（「没有名字」只有一种表示），并截到 MAX_NAME_LENGTH。
        String normalized = name == null ? null : name.strip();
        if (normalized != null && normalized.isEmpty()) {
            normalized = null;
        }
        if (normalized != null && normalized.length() > MAX_NAME_LENGTH) {
            normalized = normalized.substring(0, MAX_NAME_LENGTH);
        }
        name = normalized;
    }

    public static final Codec<Area> CODEC = RecordCodecBuilder.create(builder -> builder.group(
        Codec.INT.fieldOf("min_x").forGetter(Area::minX),
        Codec.INT.fieldOf("min_y").forGetter(Area::minY),
        Codec.INT.fieldOf("min_z").forGetter(Area::minZ),
        Codec.INT.fieldOf("max_x").forGetter(Area::maxX),
        Codec.INT.fieldOf("max_y").forGetter(Area::maxY),
        Codec.INT.fieldOf("max_z").forGetter(Area::maxZ),
        Codec.STRING.optionalFieldOf("name").forGetter(area -> java.util.Optional.ofNullable(area.name()))
    ).apply(builder, (minX, minY, minZ, maxX, maxY, maxZ, name) ->
        new Area(minX, minY, minZ, maxX, maxY, maxZ, name.orElse(null))));

    /**
     * 网络编码：六个 VAR_INT + 一个 UTF 名字（空串 = 无名字）。
     * <p>手写而不是 {@code StreamCodec#composite}：这条路是 <b>7</b> 个分量，而 composite 的分量重载
     * 只到 6。手写时直接复用 {@link ByteBufCodecs#VAR_INT} / {@link ByteBufCodecs#STRING_UTF8} 两个
     * 既有编码器（逐字节与原 6 段格式兼容），并把 {@code null} 名字写成空串（网络边界不接受 null）。</p>
     */
    public static final StreamCodec<ByteBuf, Area> STREAM_CODEC = StreamCodec.of(
        (buf, area) -> {
            ByteBufCodecs.VAR_INT.encode(buf, area.minX());
            ByteBufCodecs.VAR_INT.encode(buf, area.minY());
            ByteBufCodecs.VAR_INT.encode(buf, area.minZ());
            ByteBufCodecs.VAR_INT.encode(buf, area.maxX());
            ByteBufCodecs.VAR_INT.encode(buf, area.maxY());
            ByteBufCodecs.VAR_INT.encode(buf, area.maxZ());
            ByteBufCodecs.STRING_UTF8.encode(buf, area.name() == null ? "" : area.name());
        },
        buf -> new Area(
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf))
    );

    /** 由两个角点构造（自动归一化）。 */
    public static Area of(final BlockPos first, final BlockPos second) {
        return new Area(first.getX(), first.getY(), first.getZ(),
            second.getX(), second.getY(), second.getZ());
    }

    /** 以某一点为中心的 1×1×1 区域（「新建」按钮的种子）。 */
    public static Area single(final BlockPos pos) {
        return new Area(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
    }

    public long sizeX() {
        return (long) maxX - (long) minX + 1L;
    }

    public long sizeY() {
        return (long) maxY - (long) minY + 1L;
    }

    public long sizeZ() {
        return (long) maxZ - (long) minZ + 1L;
    }

    /** 体积（格）。体积上限由 Config 判定，本方法只做饱和乘法。 */
    public long volume() {
        return saturatingMultiply(saturatingMultiply(sizeX(), sizeY()), sizeZ());
    }

    public boolean contains(final BlockPos pos) {
        return pos != null && contains(pos.getX(), pos.getY(), pos.getZ());
    }

    public boolean contains(final int x, final int y, final int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** 渲染 / 实体查询用包围盒：两个角各占一格，因此上界 +1（与蓝图 {@code expandTowards(1,1,1)} 同口径）。 */
    public AABB toAabb() {
        return new AABB(minX, minY, minZ, (double) maxX + 1.0D, (double) maxY + 1.0D, (double) maxZ + 1.0D);
    }

    /** 非法区域（坐标越界 / 单边过长 / 体积超配置）。返回 {@code null} 表示合法。 */
    public String invalidReason(final long maxVolume) {
        if (minX < -WORLD_LIMIT || maxX > WORLD_LIMIT
            || minZ < -WORLD_LIMIT || maxZ > WORLD_LIMIT
            || minY < -256 || maxY > 4096) {
            return "out_of_world";
        }
        if (sizeX() > MAX_SIDE || sizeY() > MAX_SIDE || sizeZ() > MAX_SIDE) {
            return "side_too_long";
        }
        if (volume() > Math.max(1L, maxVolume)) {
            return "volume_too_large";
        }
        return null;
    }

    /** 平移（复制 / 定位用；名字随行，几何改变）。 */
    public Area offset(final int dx, final int dy, final int dz) {
        return new Area(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz, name);
    }

    /** 复制一份同尺寸、整体平移 +1 格的区域（「复制」按钮的种子；名字保留）。 */
    public Area shifted() {
        return offset(1, 0, 0);
    }

    /** 最大边长（界面展示 / 提示用）。 */
    public long maxSide() {
        return Math.max(sizeX(), Math.max(sizeY(), sizeZ()));
    }

    /** 「(x1,y1,z1)~(x2,y2,z2)」的紧凑文本（界面 / 日志；不含维度）。 */
    public String describe() {
        return "(" + minX + "," + minY + "," + minZ + ")~(" + maxX + "," + maxY + "," + maxZ + ")";
    }

    /**
     * 起点 / 终点分开的两段短文本（列表第二行「两行显示」用）。
     * <p>为什么要拆开：玩家投诉「坐标太长被挡住看不清，看不出终点」。把「从」和「到」放在两行后，
     * 单行宽度直接减半，再配合 {@code font.width} 量宽，两个角点就都能完整显示
     * （见 {@code AreaSelectorScreen} 的 {@code coordinateLines}）。</p>
     */
    public String describeMin() {
        return minX + "," + minY + "," + minZ;
    }

    /** 终点那一段短文本（与 {@link #describeMin()} 成对）。 */
    public String describeMax() {
        return maxX + "," + maxY + "," + maxZ;
    }

    /** 「尺寸 × 体积」一行摘要（列表 / tooltip 用）：{@code 5×3×2 = 30 格}。 */
    public String describeSize() {
        return sizeX() + "×" + sizeY() + "×" + sizeZ() + " = " + volume() + " 格";
    }

    /**
     * 是不是<b>同一块地</b>（只比六个坐标，<b>不比名字</b>）。
     * <p>去重判据就是它：同维度（由 {@link AreaSelection} 负责）+ {@link #sameGeometry(Area)}
     * ⇒ 拒绝新增并提示「已存在相同区域」。名字被刻意排除，否则「改个名字」就能绕过去重。</p>
     */
    public boolean sameGeometry(@Nullable final Area other) {
        return other != null
            && minX == other.minX && minY == other.minY && minZ == other.minZ
            && maxX == other.maxX && maxY == other.maxY && maxZ == other.maxZ;
    }

    /** 换名字（几何与其它字段不变）。{@code null} / 空白 = 清除名字。 */
    public Area withName(@Nullable final String newName) {
        return new Area(minX, minY, minZ, maxX, maxY, maxZ, newName);
    }

    /** 列表显示用的名字：没有名字时给「未命名」，绝不返回 null（调用方少一层判空）。 */
    public String displayName(final String unnamed) {
        return name == null || name.isEmpty() ? unnamed : name;
    }

    /** 与另一个区域是否相交（界面提示「和 XYZ 范围有没有交集」用；吸收口径是「只按区域」，不做交集判定）。 */
    public boolean intersects(final Area other) {
        return other != null
            && minX <= other.maxX && maxX >= other.minX
            && minY <= other.maxY && maxY >= other.minY
            && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    private static long saturatingMultiply(final long first, final long second) {
        if (first <= 0L || second <= 0L) {
            return 0L;
        }
        return first > Long.MAX_VALUE / second ? Long.MAX_VALUE : first * second;
    }
}
