package cretae.cookiewyq.rs_create_compat.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * 本模组「玩法型成就」的通用自定义 criterion 触发器。
 *
 * <p><b>为什么自定义</b>：原版 criterion（inventory_changed / placed_block 一类）只能表达
 * 「玩家手上 / 脚下发生了什么」，表达不了「装填器把加农炮喂饱并自动打印完一张蓝图」这种
 * 机器组的复合事件，而用户要的正是这类成就。因此这里按原版 {@code PlayerTrigger} 的写法
 * 做一个「只带可选 player 谓词、其余全靠代码在正确时机 fire」的触发器。</p>
 *
 * <p><b>为什么一个类注册多次</b>：registry 名（= JSON 里的 {@code trigger} 值）决定哪些成就
 * 在监听，同一个实现类可以注册成多个独立触发器 —— 原版就是这么复用 {@code PlayerTrigger} 的
 * （LOCATION / SLEPT_IN_BED / TICK 共用一份实现）。注册点见 {@link RsccAdvancements}。</p>
 *
 * <p><b>服务端 / 客户端边界</b>：{@link ServerPlayer} 才有 {@code PlayerAdvancements}，
 * 因此 {@link #fire(ServerPlayer)} 只在服务端调用；所有调用点都位于服务端 tick / 方块实体
 * 服务端逻辑里（客户端连世界对象都没有），见 {@link RsccAdvancements} 里各语义触发点。</p>
 */
public class RsccCriterionTrigger extends SimpleCriterionTrigger<RsccCriterionTrigger.TriggerInstance> {
    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    /**
     * 把「某台机器刚做完某件玩法事件」广播给所有监听本触发器的成就。
     * <p>判定完全交给原版：没有监听者（没装对应成就 / 已达成过）时是一个空集合遍历，
     * 因此这里不需要自己缓存「是否已达成」。</p>
     */
    public void fire(final ServerPlayer player) {
        trigger(player, instance -> true);
    }

    /**
     * 成就实例：与原版 {@code PlayerTrigger.TriggerInstance} 完全同构 ——
     * 只有可选的 player 谓词，因此 JSON 里 {@code "criteria": {"x": {"trigger": "..."}}}
     * 不写 {@code conditions} 也能解析。
     */
    public record TriggerInstance(Optional<ContextAwarePredicate> player)
        implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player)
            ).apply(instance, TriggerInstance::new)
        );
    }
}
