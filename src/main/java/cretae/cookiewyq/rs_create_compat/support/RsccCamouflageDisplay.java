package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>「指向信息模组」（Jade / WAILA 一类）该显示什么</b>的唯一数据来源（用户第 2 条）。
 *
 * <h2>用户要求的三条口径（照抄，不另立）</h2>
 * <ol>
 *     <li><b>图标</b>＝<b>填充方块</b>的图标（伪装的意图就是「看起来像一个普通方块」，
 *     指向信息模组的图标若还画线缆，就和眼睛看到的完全矛盾）；</li>
 *     <li><b>文本</b>＝<b>原本部件类型</b>（线缆 / 传动杆 / 流体管道 …）<b>＋「（被伪装）」</b>
 *     —— 玩家既要知道“看起来是什么”，更要知道“它到底是什么”，否则维修时会被彻底误导；</li>
 *     <li><b>详细信息（ID 等）</b>＝仍然按<b>原部件</b>显示
 *     （ID 是给调试 / 排错用的真源，绝不能换成外壳材质的 id，否则玩家照着一个不存在的方块 ID 排查问题）。</li>
 * </ol>
 * <p>三条口径都只读 {@link RsccCamouflage} 的状态（服务端记录 / 客户端只读镜像，
 * 双端同源），本类<b>不缓存、不写状态</b>，因此不会与游戏世界状态漂移。</p>
 *
 * <h2>为什么单独一个类（而不是把三条口径写在 Jade 插件里）</h2>
 * <p>本类只依赖 Minecraft 自己的类型，因此：① 它是「被伪装方块该显示什么」的唯一真源，
 * Jade 插件（{@code client/jade/RsccJadePlugin}）<b>只负责把结果交给 Jade</b>，一行判定都不重写；
 * ② 将来若要接别的指向信息模组（TOP 一类），复用同一份口径即可，不会出现第二份实现。
 * 用户第 2 条的三条要求就写在这个文件里，改口径只改这里。</p>
 *
 * <h2>Jade 接入现状（第 8 轮）</h2>
 * <ul>
 *     <li>Jade 已作为<b>可选依赖</b>引入：制品随工程走（{@code libs/jade-15.10.5+neoforge.jar}），
 *     {@code build.gradle} 用 {@code compileOnly(files(...)) + runtimeOnly(files(...))} 引用 ——
 *     <b>不经过任何 Maven 远程解析</b>（起因：用户机器到 Modrinth CDN 的 TLS 证书链校验失败），
 *     {@code neoforge.mods.toml} 里声明为 {@code type = "optional"}；</li>
 *     <li>插件类是 {@code client/jade/RsccJadePlugin}（{@code @WailaPlugin} + {@code IWailaPlugin}
 *     + {@code registerBlockComponent}）：图标走 {@link #icon}、文本走 {@link #label}、
 *     详细信息 ID 由 Jade 按真实方块给出（那本来就是原部件，见 {@link #detailId} 的口径声明）；</li>
 *     <li><b>未安装 Jade 一定不影响启动</b>：插件类只被 Jade 的注解扫描发现，没有任何别处引用它，
 *     因此 JVM 不会去解析 {@code snownee.jade.*}；本类本身也不引用 Jade 的任何类型。</li>
 * </ul>
 */
public final class RsccCamouflageDisplay {
    /**
     * 「（被伪装）」这句后缀的语言键：值是 {@code "%s（被伪装）"} / {@code "%s (Camouflaged)"}，
     * {@code %s} ＝ 原部件自己的名字（线缆 / 传动杆 / 流体管道 …）。
     *
     * <p><b>为什么用带占位符的整句</b>：拼接「名字 + 括号」看起来等价，但中文与英文的括号 / 空格习惯
     * 不同（中文全角括号无空格、英文半角括号前有空格），交给语言文件才能两语都自然，
     * 也不会有「玩家自改语言包后括号错位」的问题。</p>
     */
    public static final String SUFFIX_KEY = "jade.rs_create_compat.camouflaged";

    private RsccCamouflageDisplay() {
    }

    /** 这一格是不是「已填充方块的伪装格」（＝有东西可显示；「空壳」不参与）。 */
    public static boolean hasFilledBlock(final BlockGetter level, final BlockPos pos) {
        return level != null && pos != null && RsccCamouflage.material(level, pos) != null;
    }

    /**
     * <b>图标 ＝ 填充方块</b>（用户第 2 条）。
     *
     * @param original 没被伪装 / 还没填充 / 填充方块没有对应物品时原样返回它
     *                 （调用方传「这一格真正的部件图标」，即原部件自己的图标）
     * @return 永远不会是 {@code null}（最差情况返回 {@code original}）
     */
    public static ItemStack icon(final BlockGetter level, final BlockPos pos, final ItemStack original) {
        if (!hasFilledBlock(level, pos)) {
            return original;
        }
        final BlockState material = RsccCamouflage.material(level, pos);
        final ItemStack filled = new ItemStack(material.getBlock());
        // 个别方块没有对应物品（技术方块 / 只作为外观的状态）：退回原部件图标，绝不显示一个空槽
        return filled.isEmpty() ? original : filled;
    }

    /**
     * <b>文本 ＝ 原部件类型 ＋「（被伪装）」</b>（用户第 2 条）。
     *
     * @param original 原部件自己的名字（例如线缆 / 流体管道 / 传动杆的
     *                 {@code BlockState#getBlock()#getName()}）
     * @return 没被伪装时原样返回 {@code original}（绝不加后缀，否则正常部件也会被标成「被伪装」）
     */
    public static Component label(final BlockGetter level, final BlockPos pos, final Component original) {
        if (!hasFilledBlock(level, pos)) {
            return original;
        }
        return Component.translatable(SUFFIX_KEY, original);
    }

    /**
     * <b>详细信息里的 ID 仍按原部件</b>（用户第 2 条）：只从<b>原部件自己的方块状态</b>取注册名，
     * 任何情况下都不会返回外壳材质的 id。
     *
     * <p>参数名刻意叫 {@code original}：这个方法的存在本身就是一句口径声明 ——
     * 想显示 ID 的调用方把「这一格真正的部件状态」传进来即可，永远拿不到材质 id。</p>
     */
    public static ResourceLocation detailId(final BlockState original) {
        return BuiltInRegistries.BLOCK.getKey(original.getBlock());
    }
}
