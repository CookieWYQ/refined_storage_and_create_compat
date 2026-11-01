package cretae.cookiewyq.rs_create_compat.client.jade;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.compat.jade.RsccReceiverJadePayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * <b>网络资源接收器的 Jade 提示（客户端一侧）</b>：只补一行「接收器名字 / 未命名=不可用」。
 *
 * <h2>为什么这里没有「按住 Shift 看明细」</h2>
 * <p>第 95 轮用户原话：「<i>按下 Shift 显示详情什么的这玩意 Jade 已经帮你实现了</i>」。
 * 复核结论（字节码证据见 {@link RsccReceiverJadePayload} 类注释）：接收器暴露
 * {@code Capabilities.ItemHandler.BLOCK} / {@code Capabilities.FluidHandler.BLOCK}，
 * 而 Jade Universal 插件在服务端就是按 {@code Level#getCapability(..., side = null)} 读这两个能力，
 * 所以「缓存里有什么」Jade 自己会显示、也会自己处理 Shift 展开。</p>
 * <p>本 provider 因此<b>不</b>判任何按键、<b>不</b>碰方块实体、<b>不</b>列明细：
 * 只把「Jade 结构上取不到的那一行」（名字，属方块自身状态而非容器内容）交给 Jade。</p>
 *
 * <p>没装 Jade 一定不影响启动：本类只被同包的 {@code RsccJadePlugin} 引用，
 * 而那个类只被 Jade 的注解扫描发现 —— Jade 不在时没有任何调用方，
 * JVM 不会去解析 {@code snownee.jade.*}。</p>
 */
public final class RsccReceiverJadeProvider implements IBlockComponentProvider {
    /** 本 provider 的唯一 id（Jade 用它去重 / 建配置项，见 {@code config.jade.plugin_<modid>.<path>}）。 */
    private static final ResourceLocation UID =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "receiver");

    @Override
    public void appendTooltip(final ITooltip tooltip, final BlockAccessor accessor,
                              final IPluginConfig config) {
        if (!RsccReceiverJadePayload.hasData(accessor.getServerData())) {
            return; // 不是接收器 / 服务端没注册：一行都不加，完全放行
        }
        for (final Component line : RsccReceiverJadePayload.lines(accessor.getServerData())) {
            tooltip.add(line);
        }
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }
}
