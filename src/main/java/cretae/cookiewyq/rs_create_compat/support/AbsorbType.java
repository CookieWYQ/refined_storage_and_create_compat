package cretae.cookiewyq.rs_create_compat.support;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 归流缓存仓的吸取来源类型：4 个互相独立的开关（每台方块实体独立持久化）。
 * <ul>
 *     <li>{@link #ITEM}：世界中掉落的物品实体（约等于原行为）；</li>
 *     <li>{@link #FLUID}：世界中的流体源方块（一格 = 1000 mB）；</li>
 *     <li>{@link #GAS}：气体（入口已预留，RS 资源体系暂无气体类型）；</li>
 *     <li>{@link #EXPERIENCE}：经验（收 <b>经验球实体</b> {@code net.minecraft.world.entity.ExperienceOrb}，
 *     把经验点并入网络存储；液态形态见 {@link XpForm}）。</li>
 * </ul>
 * <b>约定：4 个吸取开关本身不出现「未安装 XX 模组所以无法吸取」之类的提示</b>，
 * 缺少可选依赖时仅表现为「该形态当前不可用」（解析结果为空）。
 * <p><b>例外（用户明确要求）</b>：经验<b>液态形态</b>缺前置时必须<b>置灰并说明缺少哪个前置</b>
 * （见 {@link XpForm#missingDependency()}）—— 那里不能静默不可用。
 */
public enum AbsorbType {
    ITEM,
    FLUID,
    GAS,
    EXPERIENCE;

    /** 网络传输（C2S {@code SetCollectionAbsorbTogglePacket}）用：按序号编解码。 */
    public static final StreamCodec<ByteBuf, AbsorbType> STREAM_CODEC =
        ByteBufCodecs.idMapper(index -> values()[Math.floorMod(index, values().length)], AbsorbType::ordinal);
}
