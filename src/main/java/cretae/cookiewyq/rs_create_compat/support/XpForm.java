package cretae.cookiewyq.rs_create_compat.support;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

/**
 * 归流缓存仓的经验<b>存储形态</b>（由界面按钮切换，持久化在方块实体 NBT 键 {@code XpForm}）。
 *
 * <h2>本版语义（用户要求）</h2>
 * <p>两种形态的<b>收集对象完全相同</b>：世界中的<b>经验球实体</b>
 * （{@code net.minecraft.world.entity.ExperienceOrb}）。用户原话：「勾上之后呢，它是把这个
 * <b>经验球直接转换成这个流体</b>，直接转换成这一个<b>液态经验</b>，然后<b>储存起来</b>」——
 * 因此形态只决定「经验点换算成哪一种<b>存储</b>表示」，不决定「收不收球」。</p>
 * <ul>
 *     <li>{@link #ORB}（默认）：经验点 → 经验颗粒物品（3 点 / 个，机械动力原版倍率）→ 物品缓存 → 网络物品存储。</li>
 *     <li>{@link #LIQUID}：经验点 → 液态经验流体（1 点 = 1 mB）→ <b>本仓流体缓存</b> → 网络流体存储。
 *     用户明确指出「它本质上它还是这种<b>流体</b>，就是这个液态经验……所以说他是<b>归流体那一方面管的</b>」，
 *     故本形态走的就是流体那条路（流体缓存 / 网络里的流体资源），而不是「只吸世界里的源方块」。
 *     <b>需要前置</b>：该流体由「机械动力：附魔工业」（{@code create_enchantment_industry}）提供，
 *     该前置缺失时本形态 {@link #selectable()} 为 false（界面置灰 + tooltip 说明缺哪个前置）。</li>
 * </ul>
 *
 * <h2>「自动」模式已删除</h2>
 * <p>用户要求：「把自动模式删掉，因为自动模式现在好像没什么意义了」。旧 {@code AUTO}（两种来源都收）
 * 在「颗粒 / 液态」各自独立之后没有任何额外含义，因此整条判定分支一并删除 —— 行为退化为
 * <b>始终按玩家显式选择的形态</b>，不再存在任何「自动」隐含状态。</p>
 *
 * <h2>序号映射（本轮修复：这就是「装了附魔工业却点了没反应」的根因）</h2>
 * <p>历史枚举顺序是 {@code AUTO(0) / NUGGET(1) / LIQUID(2)}，当时写入的旧序号 {@code 2} 表示
 * <b>液态</b>；删除 AUTO/NUGGET 后本枚举变成 {@code ORB(0) / LIQUID(1)} ——
 * <b>液态的现行序号从 2 变成了 1</b>。</p>
 * <p>旧 {@link #byOrdinal(int)} 只把 {@code 2} 认成液态，于是<b>现行序号 {@code 1}
 * 被解成 {@link #ORB}</b>：C2S 包（{@code SetCollectionXpFormPacket} 用 {@link #STREAM_CODEC}）
 * 与菜单数据槽 21（服务端写 {@code ordinal()}、客户端读 {@link #byOrdinal(int)}）双双把液态丢掉。
 * 表现就是：按钮点了、按钮上的字与 tooltip 永远停在「经验颗粒」，液态那条路一次都不会跑
 * （用户报告「安装了附魔工业但还是点不了这个按钮 / 点了之后它还是没有生效」）。</p>
 * <p>现在 {@link #byOrdinal(int)} 同时接受现行 {@code 1} 与旧序号 {@code 2} 作为液态，其余值一律
 * 退化为 {@link #ORB}（越界 / 脏值不会把界面或逻辑带崩）。老存档本身存的是<b>名字</b>
 * （{@code CollectionCacheBlockEntity#loadXpForm} 走 {@code valueOf}），因此序号口径只影响
 * 网络包与数据槽这两个瞬态通道，改动不会把老存档读坏。</p>
 */
public enum XpForm {
    /** 经验颗粒（默认存储形态）：吸取范围内的 {@code ExperienceOrb}，按 3 点 / 个折算成经验颗粒物品入网。 */
    ORB,
    /** 液态经验（流体存储形态）：吸取范围内的 {@code ExperienceOrb}，按 1 点 = 1 mB 折算成液态经验存入流体缓存。 */
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
     * 旧存档里「液态」用的序号（历史枚举 {@code AUTO(0) / NUGGET(1) / LIQUID(2)}）。
     * <p>保留它只是为了让老存档读完仍是液态；现行 {@link #LIQUID} 的序号是 {@code 1}。</p>
     */
    private static final int LEGACY_LIQUID_ORDINAL = 2;

    /**
     * 序号 → 形态（<b>永不抛异常</b>，且兼容旧存档的旧序号）。
     * <p>数据槽 / 网络包只传序号，这里做一次收敛，避免越界值把界面或逻辑带崩。</p>
     * <p><b>为什么必须同时认 1 和 2</b>：现行 {@link #LIQUID} 的序号是 {@code 1}，
     * 而旧存档写下的液态序号是 {@code 2}。此前只认 2 ⇒ 现行序号 1 被解成 {@link #ORB}，
     * 于是菜单数据槽 21 与 C2S 包都把「液态」静默降级成「颗粒」，玩家点了按钮没有任何变化。
     * 这是<b>解码口径</b>，不是可选的兼容：{@code byOrdinal(encode(x)) == x} 必须对两个常量都成立。</p>
     */
    public static XpForm byOrdinal(final int ordinal) {
        return (ordinal == LIQUID.ordinal() || ordinal == LEGACY_LIQUID_ORDINAL) ? LIQUID : ORB;
    }

    /** 网络传输（C2S {@code SetCollectionXpFormPacket}）用：按序号编解码。 */
    public static final StreamCodec<ByteBuf, XpForm> STREAM_CODEC =
        ByteBufCodecs.idMapper(XpForm::byOrdinal, XpForm::ordinal);
}
