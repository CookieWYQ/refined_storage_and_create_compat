package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.Area;
import cretae.cookiewyq.rs_create_compat.support.AreaSelection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * S2C：归流缓存仓「查看吸取范围」的<b>服务端权威几何快照</b>（基础版 + 高级版共用）。
 *
 * <h2>为什么必须走 S2C（照抄范围充电器那条链路）</h2>
 * <p>客户端的归流缓存仓菜单是用注册期的二参工厂 {@code new CollectionCacheMenu(id, inventory)}
 * 重建的（{@code MenuType} 是普通类型，不是 {@code IMenuTypeExtension}），因此
 * {@code CollectionCacheMenu#block} 在客户端<b>恒为 null</b>：拿不到方块坐标、也读不到三轴半径。
 * 高级版此前那颗「范围」按钮是<b>纯客户端</b>的（直接读槽里选定器物品的数据组件去画框），
 * 它只覆盖了「有区域」这一种情形，而且与「本机此刻真正在扫什么」没有任何服务端校验 ——
 * 现在统一改成：<b>界面按钮 → 原版菜单按钮通道 C2S → 方块实体组快照 → 本包 S2C → 客户端只画</b>。</p>
 *
 * <h2>几何口径：本包传的是「方块区域」，不是半径</h2>
 * <p>{@link #boxes()} 的每一项都是 {@link Area}（含首含尾的方块闭区间），客户端只做
 * {@code Area#toAabb()} 这一个转换（{@code minX..maxX+1}）就得到要画的盒子 ——
 * <b>客户端不乘不除、不读半径、不读方块实体</b>，于是「画出来的框」必然等于「服务端那一刻
 * 真正在扫的范围」：</p>
 * <ul>
 *     <li>{@link #MODE_RADIUS}：<b>基础版 / 高级版没装区域选定器</b>。服务端把
 *     {@code new AABB(worldPosition).inflate(scanX, scanY, scanZ)} 这<b>一个</b>盒子
 *     （= 掉落物 / 经验球实体查询、流体源方块游标<b>逐字同一个</b>几何，见
 *     {@code CollectionCacheBlockEntity#collectionRangeBox()}）换算成方块区域下发；</li>
 *     <li>{@link #MODE_AREAS}：<b>高级版区域模式生效</b>。下发选定器里<b>已去重</b>的若干区域
 *     （{@code AreaSelection#distinctAreas()}）—— 区域模式<b>取代</b>三轴半径，两者绝不叠加；</li>
 *     <li>{@link #MODE_NO_AREA}：<b>高级版装了选定器但区域不可用</b>（区域为空 / 维度不符）。
 *     此时方块实体 {@code absorbAll} 整段跳过 ⇒ 本机<b>一点不吸</b>，因此这里
 *     {@code boxes} 为空、客户端<b>一个框都不画</b>（画半径框会谎报「正在吸这片地」）；</li>
 *     <li>{@link #MODE_NO_SELECTOR}：<b>高级版没装区域选定器</b>。同样<b>不画框</b> ——
 *     高级版的 {@code areaAbsorptionBlocked()} 在没有选定器时也返回 {@code true}，
 *     它<b>不存在</b>「按半径吸」的状态（半径口径只属于基础版）。</li>
 * </ul>
 *
 * <h2>线程</h2>
 * <p>处理体只把包转交给 {@link ClientPayloadHooks}（真正的界面判断在 {@code client} 包的
 * {@code ClientPayloadSink}），并按客户端主线程 {@code enqueueWork} 执行；
 * 本类<b>不含任何</b> {@code net.minecraft.client.*} 类型 ⇒ 专用服务端注册期安全。</p>
 *
 * @param containerId 菜单 id（客户端只更新 id 对得上的那一个界面）
 * @param mode        {@link #MODE_RADIUS} / {@link #MODE_AREAS} / {@link #MODE_NO_AREA}
 * @param boxes       要画的方块区域（已由服务端按上表口径算好；最多
 *                    {@link AreaSelection#ABSOLUTE_MAX_AREAS} 个）
 */
public record ShowCollectionRangePacket(int containerId, int mode, List<Area> boxes)
    implements CustomPacketPayload {

    /** 半径口径：{@code boxes} 恰好 1 个盒子（三轴 radius 膨胀后的扫描盒）—— <b>只有基础版</b>会发。 */
    public static final int MODE_RADIUS = 0;
    /** 区域口径：{@code boxes} 是选定器里已去重的若干区域（区域取代半径）。 */
    public static final int MODE_AREAS = 1;
    /** 高级版：区域模式开着但区域不可用（区域为空 / 维度不符）⇒ 本机不吸任何东西，{@code boxes} 为空。 */
    public static final int MODE_NO_AREA = 2;
    /** 高级版：没装区域选定器 ⇒ 本机同样不吸任何东西（没有半径回落），{@code boxes} 为空。 */
    public static final int MODE_NO_SELECTOR = 3;
    /** 最大值（解码端用它判断「未知模式」）。 */
    public static final int MODE_MAX = MODE_NO_SELECTOR;

    public static final Type<ShowCollectionRangePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(
            RS_Create_Compat.MODID, "show_collection_range"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ShowCollectionRangePacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ShowCollectionRangePacket::containerId,
            ByteBufCodecs.VAR_INT, ShowCollectionRangePacket::mode,
            // 与 AreaSelection.STREAM_CODEC 同一个 Area 编解码器（六个坐标 + 可选名字）
            Area.STREAM_CODEC.apply(ByteBufCodecs.list()), ShowCollectionRangePacket::boxes,
            ShowCollectionRangePacket::new
        );

    public ShowCollectionRangePacket {
        // 网络边界的保险：上游如果塞了超量 / null，就地截断成「客户端能安全绘制」的形态。
        // 服务端自己已经按同一上限（ABSOLUTE_MAX_AREAS = 64）夹过，这里只是不信任任何输入。
        if (boxes == null) {
            boxes = List.of();
        } else if (boxes.size() > AreaSelection.ABSOLUTE_MAX_AREAS) {
            boxes = List.copyOf(boxes.subList(0, AreaSelection.ABSOLUTE_MAX_AREAS));
        } else {
            boxes = List.copyOf(boxes);
        }
        if (mode < MODE_RADIUS || mode > MODE_MAX) {
            // 未知模式：按「不画」处理（绝不猜成半径 —— 那会画出服务端没说过的范围）
            mode = MODE_NO_AREA;
        }
    }

    public static void handle(final ShowCollectionRangePacket packet, final IPayloadContext ctx) {
        // 客户端专属处理在 ClientPayloadSink（本类保持只依赖双端类型，专用服务端安全）
        ctx.enqueueWork(() -> ClientPayloadHooks.get().showCollectionRange(packet));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
