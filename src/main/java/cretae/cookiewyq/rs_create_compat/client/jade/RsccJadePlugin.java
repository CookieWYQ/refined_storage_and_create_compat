package cretae.cookiewyq.rs_create_compat.client.jade;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.support.RsccCamouflageDisplay;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.ITooltip;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElement;
import snownee.jade.api.ui.IElementHelper;

/**
 * <b>Jade（指向信息模组）的可选接入</b>：让「被伪装的方块」在瞄准提示里显示成
 * <b>图标 = 填充方块</b>、<b>文本 = 原部件类型 +「（被伪装）」</b>、<b>详细信息 ID 仍按原部件</b>
 * （用户第 2 条）。三条口径<b>全部</b>取自 {@link RsccCamouflageDisplay} —— 本类只负责
 * 「把它们交给 Jade」，不自己判一次「是不是被伪装」（否则就是第二份口径，迟早漂移）。
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
 * <h2>为什么注册给 {@code Block.class}（所有方块）</h2>
 * <p>被伪装的方块<b>不是某一个方块类型</b>：世界那一格仍然是 RS 的线缆 / 输入总线 / 输出总线，
 * 或 Create 的流体管道 / 传动杆 —— 四五个不同的类。Jade 的注册 API 按<b>方块类</b>注册，
 * 逐个列出必然漏掉将来新增的同族方块；注册给「所有方块」+ 在方法里先问一次数据来源
 * （{@link RsccCamouflageDisplay#hasFilledBlock} 是纯记录查询，未伪装的格子第一句就返回）代价极小，
 * 却天然覆盖全部现状与将来的同族方块。</p>
 */
@WailaPlugin
public class RsccJadePlugin implements IWailaPlugin {
    /** 本插件对本模组所有方块的接入入口（只注册客户端一侧；标题 / 图标都是纯客户端表现）。 */
    @Override
    public void registerClient(final IWailaClientRegistration registration) {
        registration.registerBlockComponent(new CamouflageProvider(), Block.class);
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
