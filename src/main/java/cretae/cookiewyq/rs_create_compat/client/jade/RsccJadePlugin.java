package cretae.cookiewyq.rs_create_compat.client.jade;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.compat.jade.RsccBusJadeServerData;
import cretae.cookiewyq.rs_create_compat.support.RsccCamouflageDisplay;
import com.refinedmods.refinedstorage.common.exporter.ExporterBlock;
import com.refinedmods.refinedstorage.common.importer.ImporterBlock;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.ITooltip;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElement;
import snownee.jade.api.ui.IElementHelper;

/**
 * <b>Jade（指向信息模组）的可选接入</b>。本插件有两条互不干扰的接入：
 *
 * <ol>
 *     <li><b>被伪装的方块</b>：让它在瞄准提示里显示成 <b>图标 = 填充方块</b>、
 *     <b>文本 = 原部件类型 +「（被伪装）」</b>、<b>详细信息 ID 仍按原部件</b>（用户第 2 条）。
 *     三条口径<b>全部</b>取自 {@link RsccCamouflageDisplay} —— 本类只负责「把它们交给 Jade」，
 *     不自己判一次「是不是被伪装」（否则就是第二份口径，迟早漂移）；</li>
 *     <li><b>序列装配总线的状态</b>（用户第 8 项 / 任务 A）：与哪台执行仓绑定、按
 *     「原料 / 输入时原料 / 流体 / 成品 / 废料 / 中间产物」六节列出它此刻负责什么、
 *     输入总线是否全自动。数据由 {@link RsccBusJadeServerData} 从<b>服务端</b>方块实体取
 *     （客户端读不到，见那个类的说明），文案与分组在
 *     {@code compat/jade/RsccBusJadePayload}，客户端只按配置决定显不显示
 *     （{@code client/jade/RsccBusJadeProvider}）。</li>
 * </ol>
 *
 * <h2>为什么客户端注册与服务端注册要分成两个方法（专服安全）</h2>
 * <p>{@link #register(IWailaCommonRegistration)} 在<b>专用服务端</b>也会被 Jade 调用，
 * 因此它<b>只</b>引用 {@link RsccBusJadeServerData}（公共类型：NBT / 注册表 / 现有的方块实体桥接接口），
 * 一个 {@code net.minecraft.client.*} 类型都不碰；客户端专属的图标 / 文本提供者只出现在
 * {@link #registerClient(IWailaClientRegistration)} 里 —— 那个方法在专服上根本不会被调用。
 * 这条分工正是本工程「专服注册期绝不能引用客户端类型」硬约束的落点
 * （见 {@code tools/selfcheck_round47_dedicated_server_safety.py} 与
 * {@code tools/selfcheck_round54_jade_bus.py}）。</p>
 *
 * <h2>为什么这是「可选依赖」，未安装 Jade 一定不影响启动</h2>
 * <ul>
 *     <li>本类<b>只被 Jade 发现</b>（{@link WailaPlugin} 注解 + 实现 {@link IWailaPlugin}）：
 *     Jade 未安装时它<b>没有任何调用方</b> —— 没人会去加载它，JVM 也就不会去解析它引用的
 *     {@code snownee.jade.*} 类型，因此不存在 {@code NoClassDefFoundError}；</li>
 *     <li><b>没有静态初始化块</b>：本类与内部 provider 都没有任何静态副作用（唯一的静态常量是
 *     一个 {@link ResourceLocation}），即使被别处误触也不会做任何事；</li>
 *     <li>构建层面：Jade 是 {@code compileOnly + runtimeOnly}（见 {@code build.gradle}），
 *     {@code neoforge.mods.toml} 里声明为 {@code type = "optional"}，缺席时加载器不会拦。</li>
 * </ul>
 * <p>Jade 的插件发现走它自己的注解扫描（{@code @WailaPlugin} 类由 Jade 在加载期收集），
 * 因此本类必须留在<b>本模组自己的 jar</b> 里（不能搬去别的源集，否则它扫不到）。</p>
 *
 * <h2>为什么伪装提供者注册给 {@code Block.class}（所有方块）</h2>
 * <p>被伪装的方块<b>不是某一个方块类型</b>：世界那一格仍然是 RS 的线缆 / 输入总线 / 输出总线，
 * 或 Create 的流体管道 / 传动杆 —— 四五个不同的类。Jade 的注册 API 按<b>方块类</b>注册，
 * 逐个列出必然漏掉将来新增的同族方块；注册给「所有方块」+ 在方法里先问一次数据来源
 * （{@link RsccCamouflageDisplay#hasFilledBlock} 是纯记录查询，未伪装的格子第一句就返回）代价极小，
 * 却天然覆盖全部现状与将来的同族方块。</p>
 *
 * <h2>为什么总线那两条注册只给「输入总线 / 输出总线」两个类</h2>
 * <p>总线那条路要在<b>服务端</b>跑一遍组装（读归属 + 读类别快照）。注册给所有方块等于玩家看每一格
 * 都白跑一次；注册给这两个具体类则只有真的在看总线时才跑（Jade 的层级查找自动覆盖两个类的染色变体）。</p>
 */
@WailaPlugin
public class RsccJadePlugin implements IWailaPlugin {
    /** 本插件对本模组所有方块的接入入口（只注册客户端一侧；标题 / 图标都是纯客户端表现）。 */
    @Override
    public void registerClient(final IWailaClientRegistration registration) {
        registration.registerBlockComponent(new CamouflageProvider(), Block.class);
        // 总线状态：输入总线 / 输出总线各注册同一条文本 provider（服务端数据的两条注册见 register()）
        final RsccBusJadeProvider bus = new RsccBusJadeProvider();
        registration.registerBlockComponent(bus, ImporterBlock.class);
        registration.registerBlockComponent(bus, ExporterBlock.class);
    }

    /**
     * 公共注册（<b>专用服务端也会走这里</b>）：把「总线状态」的服务端数据提供者挂到两条总线上。
     *
     * <p><b>为什么放在这里而不是自己发同步包</b>：Jade 官方的服务端数据通道就是为这件事准备的 ——
     * 客户端请求、目标校验、只发给正在看这一格的玩家，全部由 Jade 负责；本模组一个自定义包都不用加。
     * 两个方块类<b>共用同一个 provider 实例</b>：它无状态，且 Jade 按 UID 去重 / 排序，
     * 多个实例只会让「同一个 UID 出现在两张表里」这种无意义的歧义出现。</p>
     *
     * <p><b>本方法内一个字都不能提客户端类型</b>：{@code RsccBusJadeServerData} 与它调用的
     * {@code RsccBusJadePayload} 都只用公共类型（NBT / 注册表 / {@code Component}）。</p>
     */
    @Override
    public void register(final IWailaCommonRegistration registration) {
        final RsccBusJadeServerData provider = new RsccBusJadeServerData();
        registration.registerBlockDataProvider(provider, ImporterBlock.class);
        registration.registerBlockDataProvider(provider, ExporterBlock.class);
    }

    /**
     * 「被伪装方块」的显示提供者：图标 + 一行文本，两个方法都先问
     * {@link RsccCamouflageDisplay}（唯一数据来源），不是伪装格就<b>原样放行</b>，
     * 于是普通方块的提示与没装本模组时逐字相同。
     */
    private static final class CamouflageProvider implements IBlockComponentProvider {
        /** 本 provider 的唯一 id（Jade 用它做配置项 / 去重；命名空间用本模组 id）。 */
        private static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "camouflage");

        /**
         * <b>图标 = 填充方块</b>：返回 {@code null} 表示「不改」，让 Jade 用它原来的图标
         * （原部件自己的图标）—— 未被伪装 / 还没填充 / 填充方块没有对应物品时都走这条路。
         *
         * <p>优先级取 Jade 的「最高档」：Jade 会把把条目的图标交给<b>优先级最高</b>且非空的
         * provider，而本模组的意图正是<b>替换</b>掉「原部件图标」（眼睛看到的是填充方块，
         * 图标也必须一致），因此必须比 Jade 内置的物品图标那条更高。</p>
         */
        @Override
        @Nullable
        public IElement getIcon(final BlockAccessor accessor, final IPluginConfig config,
                                @Nullable final IElement currentIcon) {
            final ItemStack filled = RsccCamouflageDisplay.icon(accessor.getLevel(), accessor.getPosition(),
                ItemStack.EMPTY);
            return filled.isEmpty() ? null : IElementHelper.get().item(filled);
        }

        /**
         * <b>文本 = 原部件类型 +「（被伪装）」</b>：被伪装时补上一行（原部件自己的名字 +
         * 语言文件里的后缀），让玩家既知道「看起来是什么」也知道「它到底是什么」。
         *
         * <p>不是伪装格时<b>一行都不加</b>（{@link RsccCamouflageDisplay#hasFilledBlock} 先短路），
         * 因此不会给普通方块多出任何提示行。详细信息的 ID（方块 id / 模组名）由 Jade 自己按
         * 真实方块给出 —— 那本来就是原部件，不需要也不允许本类改写。</p>
         */
        @Override
        public void appendTooltip(final ITooltip tooltip, final BlockAccessor accessor, final IPluginConfig config) {
            if (!RsccCamouflageDisplay.hasFilledBlock(accessor.getLevel(), accessor.getPosition())) {
                return; // 不是「已填充方块的伪装格」：完全交给 Jade 的默认表现
            }
            final Component label = RsccCamouflageDisplay.label(accessor.getLevel(), accessor.getPosition(),
                accessor.getBlockState().getBlock().getName());
            if (label != null) {
                tooltip.add(label);
            }
        }

        @Override
        public ResourceLocation getUid() {
            return UID;
        }

        /** Jade 的优先级：数值越大越优先（{@code 9999} = 最高档，用来覆盖内置的物品图标）。 */
        @Override
        public int getDefaultPriority() {
            return 9999;
        }
    }
}
