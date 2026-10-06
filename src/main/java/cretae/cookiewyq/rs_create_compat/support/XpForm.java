package cretae.cookiewyq.rs_create_compat.support;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

/**
 * 归流缓存仓的经验<b>目标形态</b>（由界面按钮切换，持久化在方块实体 NBT 键 {@code XpForm}）。
 *
 * <h2>本版语义（用户要求）</h2>
 * <ul>
 *     <li>{@link #ORB}：吸取世界中的<b>经验球实体</b>（{@code net.minecraft.world.entity.ExperienceOrb}），
 *     把它的经验点并入网络存储。用户原话：「你好像是收集这个<b>经验颗粒</b>，我要求你收集的是
 *     <b>经验这个实体</b>（经验球）」「在匹配区里匹配经验颗粒有个屁用」——
 *     因此本形态<b>不再</b>以「经验颗粒物品」为收集对象。</li>
 *     <li>{@link #LIQUID}：只吸液态经验（流体形态，必须以世界中的流体源方块存在）。
 *     <b>需要前置</b>：该流体目前由机械动力：覆膜工艺（{@code create_enchantment_industry}）提供，
 *     未安装该前置时本形态 {@link #selectable()} 为 false（界面置灰 + tooltip 说明缺哪个前置）。</li>
 * </ul>
 *
 * <h2>「自动」模式已删除</h2>
 * <p>用户要求：「把自动模式删掉，因为自动模式现在好像没什么意义了」。旧 {@code AUTO}（两种来源都收）
 * 在「球 / 液态」各自独立之后没有任何额外含义，因此整条判定分支一并删除 —— 行为退化为
 * <b>始终按玩家显式选择的形态</b>，不再存在任何「自动」隐含状态。</p>
 *
 * <h2>旧存档容错</h2>
 * <p>历史枚举顺序是 {@code AUTO(0) / NUGGET(1) / LIQUID(2)}。{@link #byOrdinal(int)} 把旧序号
 * 映射到新语义：{@code 2 → LIQUID}（液态语义未变），其余（含已删除的 AUTO 与 NUGGET）一律
 * 退化为 {@link #ORB} —— 于是老存档读到旧字段不会异常，也不会莫名其妙切到液态。</p>
 */
public enum XpForm {
    /** 经验球实体（默认）：吸取范围内的 {@code ExperienceOrb} 并把经验点并入网络存储。 */
    ORB,
    /** 液态经验（流体）：只吸世界中的经验流体源方块；需要「机械动力：覆膜工艺」提供该流体。 */
    LIQUID;

    /** 该形态在<b>当前环境</b>下是否可选（缺前置的形态不可选；判定走既有工具方法，不硬编码版本号）。 */
    public boolean selectable() {
        return this != LIQUID || OptionalDeps.hasEnchantmentIndustryExperienceFluid();
    }

    /**
     * 前置缺失时说明「缺哪个前置」（可选 → {@code null}）。
     * <p>界面据此给 tooltip 补一行说明，而不是静默不可用（用户要求「置灰 + tooltip 说明缺少哪个前置」）。</p>
     */
    @Nullable
    public Component missingDependency() {
        if (this == LIQUID && !selectable()) {
            return Component.translatable("gui.rs_create_compat.collection_cache.xp_form.missing_dependency",
                Component.translatable("gui.rs_create_compat.collection_cache.xp_form.required_mod"));
        }
        return null;
    }

    /**
     * 按钮循环的下一个<b>可选</b>形态（跳过缺前置的形态）。
     * <p>只有一个形态可选时返回自身（点按钮不会切到不可用的形态上）。</p>
     */
    public XpForm nextSelectable() {
        final XpForm[] values = values();
        for (int step = 1; step <= values.length; step++) {
            final XpForm candidate = values[(ordinal() + step) % values.length];
            if (candidate.selectable()) {
                return candidate;
            }
        }
        return this;
    }

    /**
     * 序号 → 形态（<b>永不抛异常</b>，且兼容旧存档的旧序号）。
     * <p>数据槽 / 网络包只传序号，这里做一次收敛，避免越界值把界面或逻辑带崩。</p>
     */
    public static XpForm byOrdinal(final int ordinal) {
        // 旧序号 2 = LIQUID（语义没变，保留）；旧序号 0 = AUTO、1 = NUGGET 已删除，统一退化为 ORB。
        return ordinal == 2 ? LIQUID : ORB;
    }

    /** 网络传输（C2S {@code SetCollectionXpFormPacket}）用：按序号编解码。 */
    public static final StreamCodec<ByteBuf, XpForm> STREAM_CODEC =
        ByteBufCodecs.idMapper(XpForm::byOrdinal, XpForm::ordinal);
}
