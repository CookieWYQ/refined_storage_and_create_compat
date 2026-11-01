package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.block.entity.InventoryProxyBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

/**
 * 容器代理器的 Jade <b>服务端数据</b>提供者（第 108 轮）。
 *
 * <h2>为什么必须走服务端</h2>
 * <p>{@link InventoryProxyBlockEntity.InnerState} 与两路格数只能在<b>服务端</b>算：客户端菜单重建后
 * 不持有方块实体（设计文档 §3.7.4），客户端也拿不到代理对象的内层能力（那需要
 * {@code ItemStack#getCapability} 在服务端世界 + 存储数据都在的上下文里跑）。Jade 的
 * 「服务端数据 → 客户端 tooltip」通道正是为这种情况准备的，所以本模组<b>不新增任何同步包</b>。</p>
 *
 * <h2>专服安全</h2>
 * <p>本类只引用 Jade 的<b>公共</b> API（{@code IServerDataProvider} / {@code BlockAccessor}）与
 * vanilla NBT / Component，一个 {@code net.minecraft.client.*} 类型都不碰。它只在 Jade 已安装时
 * 被 {@code client/jade/RsccJadePlugin#register} 引用 —— Jade 缺席时没有任何调用方，JVM 不会解析它。</p>
 */
public final class InventoryProxyJadeServerData implements IServerDataProvider<BlockAccessor> {
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(
        RS_Create_Compat.MODID, InventoryProxyJadePayload.SERVER_DATA_ID);

    @Override
    public void appendServerData(final CompoundTag data, final BlockAccessor accessor) {
        if (accessor.getBlockEntity() instanceof InventoryProxyBlockEntity proxy) {
            InventoryProxyJadePayload.write(data,
                !proxy.proxyStack().isEmpty(),
                proxy.innerState(),
                proxy.innerItemSlots(),
                proxy.innerFluidTanks());
        }
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }
}
