package cretae.cookiewyq.rs_create_compat.compat.jade;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

/**
 * <b>Jade 的「服务端 → 客户端」数据通道：网络资源接收器的内部缓存</b>。
 *
 * <p>为什么必须走这条通道：客户端的方块实体没有同步缓存内容（接收器没有自定义
 * {@code getUpdateTag}），客户端直接查能力只会得到空仓。Jade 官方给这种需求留的就是
 * {@link IServerDataProvider}：客户端发一次「我在看这一格」，服务端把这一格的 NBT
 * 回给<b>那个</b>玩家（目标校验、节流、只发一次都由 Jade 负责），
 * 因此<b>不需要新增任何同步包</b>（{@code network/**} 一个字节都不用动）。</p>
 *
 * <p>本类只被 {@code client/jade/RsccJadePlugin} 引用（那个类只在装了 Jade 时才会被扫描到），
 * 且<b>不引用任何客户端类型</b> —— 专服注册期安全。</p>
 */
public final class RsccReceiverJadeServerData implements IServerDataProvider<BlockAccessor> {
    /**
     * 本 provider 的唯一 id（Jade 用它去重 / 建配置项）。
     * <p>与客户端组件的 id 刻意分开（一个是服务端 provider、一个是客户端 provider，
     * 共用 id 会让 Jade 的去重表把它们当成同一个）。</p>
     */
    private static final ResourceLocation UID =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "receiver_data");

    @Override
    public void appendServerData(final CompoundTag tag, final BlockAccessor accessor) {
        RsccReceiverJadePayload.write(tag, accessor.getLevel(), accessor.getPosition(),
            accessor.getBlockEntity());
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }
}
