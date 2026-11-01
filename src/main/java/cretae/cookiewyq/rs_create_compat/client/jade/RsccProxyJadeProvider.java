package cretae.cookiewyq.rs_create_compat.client.jade;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.InventoryProxyJadePayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * 容器代理器的 Jade 提示（客户端一侧，第 108 轮）：只补「内容物事实」那一两行。
 *
 * <h2>为什么这里只加数字、不自己列内容物</h2>
 * <p>用户第 95 轮已明确：「<i>让 Jade 显示里面有什么东西的这些方块，你的作用只是把这一个接口暴露出去，
 * 并不需要你自己写……这玩意 Jade 已经帮你实现了</i>」。所以物品格、流体罐的<b>内容</b>仍然由 Jade 的
 * Universal 插件按代理器暴露的 {@code Capabilities.ItemHandler.BLOCK} /
 * {@code FluidHandler.BLOCK} 自己采集、自己排版、自己管 Shift。</p>
 * <p>本 provider 补的是 Jade <b>结构上取不到</b>的那部分：代理器<i>实际认到几路、每路多少格</i>。
 * 没有这一行，玩家看到「只显示流体」时无法判断是「真的没物品」还是「物品那路读不到」——
 * 只能靠猜，这正是用户第 108 条投诉的观感来源。</p>
 *
 * <h2>为什么客户端只读 {@code getServerData()}</h2>
 * <p>客户端不碰方块实体（{@code getBlockEntity} 在这里一次都没有出现）：代理槽里的对象内容在客户端
 * 并不可信，内层格数必须由服务端算完下发（见 {@code support/InventoryProxyJadeServerData}）。</p>
 */
public final class RsccProxyJadeProvider implements IBlockComponentProvider {
    /** 本 provider 的唯一 id（Jade 用作配置键 {@code config.jade.plugin_<modid>.proxy}）。 */
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(
        RS_Create_Compat.MODID, InventoryProxyJadePayload.COMPONENT_ID);

    @Override
    public void appendTooltip(final ITooltip tooltip, final BlockAccessor accessor,
                              final IPluginConfig config) {
        if (!InventoryProxyJadePayload.hasData(accessor.getServerData())
            || !InventoryProxyJadePayload.hasObject(accessor.getServerData())) {
            return; // 不是容器代理器 / 代理槽为空：一行都不加，完全放行
        }
        for (final Component line : InventoryProxyJadePayload.lines(accessor.getServerData())) {
            tooltip.add(line);
        }
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }
}
