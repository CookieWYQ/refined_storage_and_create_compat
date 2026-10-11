package cretae.cookiewyq.rs_create_compat.client.jade;

import cretae.cookiewyq.rs_create_compat.Config;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.tooltip.RsccTooltipLayers;
import cretae.cookiewyq.rs_create_compat.compat.jade.RsccBusJadePayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * <b>序列装配总线的 Jade 提示（客户端一侧）</b>（用户第 8 项 / 任务 A）。
 *
 * <p>本类只做两件事：①<b>按配置决定显不显示</b>；②把
 * {@link RsccBusJadePayload#lines} 已经算好的行交给 Jade。所有「显示什么」的口径都在那个
 * 公共类里（它不引用任何客户端类型，因此服务端注册期也安全）。</p>
 *
 * <h2>显示时机（用户第 8 项：配置项，默认一直显示）</h2>
 * <p>{@code Config#jadeBusTooltipShiftOnly}：{@code false}（默认）＝<b>一直显示</b>；
 * {@code true}＝<b>按住 Shift 才显示</b>，没按住时只留一行「按住 Shift 查看总线状态」，
 * 免得玩家以为这台总线没有提示。判据走 {@link RsccTooltipLayers#held}
 * （本工程「按住 Shift」的唯一实现），因此这里的 Shift 与其它 tooltip 逐字同义。</p>
 *
 * <h2>为什么不覆盖 {@code getDefaultPriority()}</h2>
 * <p>本 provider 只<b>追加文字行</b>，不碰图标：既有「伪装方块」那条会用 {@code 9999}
 * （最高档）是为了<b>替换</b> Jade 内置的物品图标。Jade 内置的方块名 / 模组名那几条都在默认档，
 * 本 provider 也留在默认档，于是这几行与它们的相对顺序由 Jade 自己决定（跑在同一个波段里），
 * 不会出现「本模组的行抢到方块名前面」这种反客为主。</p>
 *
 * <h2>缺 Jade 一定不影响启动</h2>
 * <p>本类只被同包的 {@code RsccJadePlugin} 引用，而那个类只被 Jade 的注解扫描发现 ——
 * Jade 不在时没有任何调用方，JVM 不会去解析 {@code snownee.jade.*}，
 * 自然也不会解析本类引用的客户端类型。</p>
 */
public final class RsccBusJadeProvider implements IBlockComponentProvider {
    /** 本 provider 的唯一 id（Jade 用它去重 / 建配置项，见 {@code config.jade.plugin_<modid>.<path>}）。 */
    private static final ResourceLocation UID =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "bus");

    /** 「按 Shift 才显示」档位下的提示行（只在这条提示被藏起来时出现）。 */
    private static final String LANG = "gui.rs_create_compat.jade_bus.";

    @Override
    public void appendTooltip(final ITooltip tooltip, final BlockAccessor accessor,
                              final IPluginConfig config) {
        if (!RsccBusJadePayload.hasData(accessor.getServerData())) {
            return; // 没有本模组那条数据（不是本模组总线 / 服务端没注册）：一行都不加，完全放行
        }
        if (Config.jadeBusTooltipShiftOnly && !RsccTooltipLayers.held(RsccTooltipLayers.Layer.SHIFT)) {
            // 「按 Shift 显示」档位且没按住：只说一句怎么看，绝不半显示（半显示等于误导）
            tooltip.add(Component.translatable(LANG + "hold"));
            return;
        }
        for (final Component line : RsccBusJadePayload.lines(accessor.getServerData())) {
            tooltip.add(line);
        }
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }
}
