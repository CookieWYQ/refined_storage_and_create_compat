package cretae.cookiewyq.rs_create_compat.block;

import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * 容器代理器「每个面做什么」的四态枚举（六面各一个值，逐面存 NBT）。
 *
 * <h2>为什么是「每面一个枚举」而不是「按方块朝向推导」</h2>
 * <p>设计文档 {@code docs/CONTAINER_PROXY_DESIGN.md} §4.3 / §6 D1 的结论是：
 * <b>每个底层库存只应被网络「读」一次</b>（RS 2.0.9 不按源方块去重）。但「哪个面是哪个角色」
 * 由玩家决定后，如果又按 {@code FACING} 推导，旋转方块就会让同一个底层库存漂到另一个面上，
 * 外部缓存（{@code BlockCapabilityCache}）还会拿着旧的 handler（设计文档 §5.3 / R14）。
 * 因此本方块<b>不做水平朝向</b>：六面是绝对方向，角色直接逐面存下来。</p>
 *
 * <h2>内容物为什么分成「主 / 备」两档（第 96 轮用户第 3 条）</h2>
 * <p>RS 2.0.9 的网络把每个外部存储的库存各自求和（{@code ExternalStorage#getStored} 逐 source 累加），
 * 而 {@code ExternalStorage} 的库存快照来自 handler 的 {@code getStackInSlot}
 * （{@code api/network/impl/node/externalstorage/} + {@code CapabilityCache#getItemAmountIterator}）。
 * 若同一个底层库存被两个内容物面同时暴露，终端数量就是两份之和 —— 这不是复制物品（真实抽出量
 * 永不超过实际数量），而是<b>同一份库存被数了两遍</b>。</p>
 * <p>所以内容物面拆成两档：</p>
 * <ul>
 *     <li>{@link #CONTENTS}（<b>主</b>）：完整读写，RS 看得见内容物（{@code ContentsItemView} /
 *     {@code ContentsFluidView}）；</li>
 *     <li>{@link #CONTENTS_INPUT}（<b>备·仅进</b>）：只接受输入。{@code insertItem} / {@code fill}
 *     真实写进同一个底层库存（守恒），但 {@code getStackInSlot} / {@code getFluidInTank} /
 *     {@code extractItem} / {@code drain} 一律返回空 ⇒ RS 在它上面读到的库存恒为空、不参与求和，
 *     而漏斗 / 溜槽 / 管道仍然能往里推（{@code ContentsInputItemView} /
 *     {@code ContentsInputFluidView}）。</li>
 * </ul>
 * <p>同一个底层库存<b>最多只有一个</b>面是 {@link #CONTENTS}：方块实体在换面 / 读档时会把
 * ordinal 最小的内容物面留作主面，其余内容物面降为 {@link #CONTENTS_INPUT}
 * （见 {@code InventoryProxyBlockEntity#normalizeFaces()}）。</p>
 *
 * <h2>{@link #PROXY_OBJECT} 同样只能有一个面（第 96 轮独立复核补充）</h2>
 * <p>原因与内容物面完全同源：每个 {@code PROXY_OBJECT} 面返回的都是<b>同一个</b>对象面视图
 * （1 槽，内容就是代理槽里的那件容器物品）。两个对象面各贴一个 RS 外部存储，同一件代理对象
 * 就会被两份 {@code ExternalStorage} 各读一次 ⇒ 终端上连「代理对象本身」都翻倍。</p>
 * <p>所以方块实体在换面 / 读档后也会把 ordinal 最小的对象面留下、其余对象面降为
 * {@link #DISABLED}。<b>没有</b>引入「对象备面」：代理槽只有一个，机器侧的第二条取出 / 写入
 * 路径会和界面 / 拆除路径抢同一件东西。</p>
 *
 * <h2>旧存档兼容</h2>
 * <p>NBT 存的是 {@link #id()} 字符串（{@code block/entity/InventoryProxyBlockEntity.java}
 * 的 {@code Faces} 复合标签），不是枚序号，所以新增一档不会挪动旧存档里的 {@code "contents"}：
 * {@link #byId(String)} 至今把 {@code "contents"} 解成 {@link #CONTENTS}，
 * {@link #byOrdinal(int)} 只用于<b>同版本</b>的 {@code ContainerData} 同步（版本内序号自洽即可）。</p>
 *
 * <h2>默认值（自检会逐面断言）</h2>
 * <p>只开两个面（其余 {@link #DISABLED}）：</p>
 * <ul>
 *     <li>{@link Direction#NORTH} = {@link #CONTENTS}（主内容物面）</li>
 *     <li>{@link Direction#SOUTH} = {@link #PROXY_OBJECT}（代理对象面）</li>
 *     <li>其余 4 面 = {@link #DISABLED}</li>
 * </ul>
 * <p>无面查询（{@code side == null}）恒按主内容物面处理（设计文档 §4.2），与这里的配置无关。</p>
 */
public enum ProxyFaceRole {
    /** 暴露「代理对象本身」（单槽）。 */
    PROXY_OBJECT("proxy_object"),
    /** 暴露「代理对象内部库存」（物品 / 流体），<b>完整读写</b>：这是主内容物面。 */
    CONTENTS("contents"),
    /**
     * 备内容物面：只接受输入。
     * <p>写入真实落到底层库存（{@code insertItem} / {@code fill} 转发内层），但对外读恒空 ——
     * RS 的外部存储在它上面读到空库存 ⇒ 不参与网络求和，因此「两个面各贴一个 RS 外部存储」
     * 不再让终端数量翻倍；漏斗 / 溜槽仍然能推进来。</p>
     */
    CONTENTS_INPUT("contents_input"),
    /** 这一面没有任何能力。 */
    DISABLED("disabled");

    private static final ProxyFaceRole[] VALUES = values();

    private final String id;

    ProxyFaceRole(final String id) {
        this.id = id;
    }

    /** NBT / 语言键用的稳定 id（不要用 {@code name()}：大小写与重命名都会破坏存档）。 */
    public String id() {
        return id;
    }

    /**
     * 是不是「内容物」这一族（主面或备面）。
     *
     * <p><b>真实用途</b>：方块实体在面归一 / 去重时用它区分「内容物面」与「对象面」
     * （见 {@code InventoryProxyBlockEntity} 里的归一逻辑），<b>不是</b>界面用来挑说明行的开关。</p>
     * <p><b>第 97 轮更正</b>：原注释写「界面用它判断要画哪一行说明」——那句已过时。界面上那条
     * 常驻说明在第 97 轮被用户明确否掉（「字太小、不喜欢那行字」）并删除，说明改由「悬停六面按钮」
     * 的 tooltip 承载（tooltip 文案按 {@code face_tip.} 前缀取，不经过本方法）。</p>
     */
    public boolean isContents() {
        return this == CONTENTS || this == CONTENTS_INPUT;
    }

    /**
     * 扳手右键 / 界面按钮的循环顺序：代理对象 → 内容物（主）→ 内容物（备·仅进）→ 禁用 → 代理对象。
     * <p>顺序按声明顺序走（升级旧存档不影响：NBT 里存的是 {@link #id()}，不读 ordinal）。</p>
     * <p>方块实体在 {@code cycleFace} 里会跳过「会被归一立刻改回去」的档：主面不降成备面，
     * 已有更小序号对象面时不再产生第二个对象面 —— 保证这一循环里没有「点了没反应」的档。</p>
     */
    public ProxyFaceRole next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }

    /** 反序列化：未知 / 空值一律退回 {@link #DISABLED}（不因手改 NBT 崩存档）。 */
    public static ProxyFaceRole byId(@Nullable final String id) {
        if (id != null) {
            for (final ProxyFaceRole role : VALUES) {
                if (role.id.equals(id)) {
                    return role;
                }
            }
        }
        return DISABLED;
    }

    /** 数据槽同步（{@code ContainerData}）用的序号；越界夹到 {@link #DISABLED}。 */
    public static ProxyFaceRole byOrdinal(final int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : DISABLED;
    }

    /** 该方向的 NBT 键名（与 {@link Direction#getSerializedName()} 同一套：down/up/north/south/west/east）。 */
    public static String nbtKey(final Direction direction) {
        return direction.getSerializedName();
    }

    /** 完整默认配置（每次返回新 map，调用方可以改）。 */
    public static Map<Direction, ProxyFaceRole> defaults() {
        final Map<Direction, ProxyFaceRole> map = new EnumMap<>(Direction.class);
        for (final Direction direction : Direction.values()) {
            map.put(direction, DISABLED);
        }
        map.put(Direction.NORTH, CONTENTS);
        map.put(Direction.SOUTH, PROXY_OBJECT);
        return map;
    }
}
