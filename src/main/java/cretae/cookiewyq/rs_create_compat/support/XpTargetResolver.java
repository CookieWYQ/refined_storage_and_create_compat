package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 「经验目标形态」解析器：把「液态经验流体 / 经验颗粒物品」这类<b>可选依赖相关</b>的资源解析
 * 从归流缓存仓方块实体中解耦出来。
 * <p><b>本版语义变化（用户要求）</b>：经验的<b>收集对象</b>是<b>经验球实体</b>
 * （{@code net.minecraft.world.entity.ExperienceOrb}）或液态经验流体源方块；
 * 经验<b>颗粒物品</b>不再是收集对象，只作为「没有液态经验流体时，经验点入网的物品折算表示」
 * （{@code create:experience_nugget}，3 点 / 个）。用户原话：「我要求你收集的是经验这个实体（经验球）」
 * 「在匹配区里匹配经验颗粒有个屁用」。</p>
 * <p>契约（供可选依赖实现方对齐）：
 * <ul>
 *     <li>方法必须<b>永不抛异常</b>、<b>永不返回非法值</b>：拿不到就返回 {@code null}；</li>
 *     <li>装有可选依赖（附魔工业）时优先返回其液态经验流体 id 与超越经验颗粒物品 id；
 *     没装时自动回退机械动力原版经验颗粒；</li>
 *     <li>调用方（归流缓存仓方块实体）自行判定经验球 / 液态经验两条来源，并在缺少某形态时静默降级，
 *     除「液态形态缺前置时置灰并说明缺哪个前置」这一处用户明确要求之外，
 *     不得产生「未安装 XX 所以无法吸取」之类的提示。</li>
 * </ul>
 * 默认实现 {@link #DEFAULT} 按<b>优先级依次尝试一组已知 id</b>，并以
 * {@link BuiltInRegistries} 的「注册表里是否真的存在」作为唯一判定标准
 * （模组加载判断无法说明 id 是否正确，注册表才可以），全部取不到就返回 {@code null}。
 */
public interface XpTargetResolver {
    /** 经验颗粒候选（按优先级）：附魔工业超越经验颗粒 → 机械动力原版经验颗粒 → 原版经验瓶（最后兜底，保证颗粒来源可用）。 */
    List<ResourceLocation> ITEM_CANDIDATES = List.of(
        ResourceLocation.fromNamespaceAndPath(OptionalDeps.MOD_CREATE_ENCHANTMENT_INDUSTRY,
            "super_experience_nugget"),
        ResourceLocation.fromNamespaceAndPath(OptionalDeps.MOD_CREATE, "experience_nugget"),
        ResourceLocation.withDefaultNamespace("experience_bottle"));

    /** 液态经验候选（按优先级）：附魔工业液态经验（目前唯一已知的液态经验流体）。 */
    List<ResourceLocation> FLUID_CANDIDATES = List.of(
        ResourceLocation.fromNamespaceAndPath(OptionalDeps.MOD_CREATE_ENCHANTMENT_INDUSTRY, "experience"));

    /** 经验颗粒（物品形态）注册名；不可用返回 {@code null}。 */
    @Nullable
    ResourceLocation xpNuggetItem();

    /** 液态经验（流体形态）注册名；不可用返回 {@code null}。 */
    @Nullable
    ResourceLocation liquidXpFluid();

    /** 默认实现：按候选优先级取「注册表里确实存在」的第一个；全都不存在就静默降级为 {@code null}。 */
    XpTargetResolver DEFAULT = new XpTargetResolver() {
        @Override
        public ResourceLocation xpNuggetItem() {
            return firstPresent(ITEM_CANDIDATES, BuiltInRegistries.ITEM);
        }

        @Override
        public ResourceLocation liquidXpFluid() {
            return firstPresent(FLUID_CANDIDATES, BuiltInRegistries.FLUID);
        }
    };

    /** 返回候选里第一个在注册表中真实存在的 id（都不存在返回 null，绝不抛异常）。 */
    private static <T> ResourceLocation firstPresent(final List<ResourceLocation> candidates,
                                                     final Registry<T> registry) {
        for (final ResourceLocation id : candidates) {
            if (id != null && registry.containsKey(id)) {
                return id;
            }
        }
        return null;
    }
}
