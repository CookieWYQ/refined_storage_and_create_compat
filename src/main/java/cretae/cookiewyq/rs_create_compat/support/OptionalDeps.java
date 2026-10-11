package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * 可选依赖探测 + 「经验」资源解析。
 * <p>
 * <b>为什么全部走「注册表按 id 查找 + 模组加载判断兜底」</b>：附魔工业 / Mekanism 都是可选依赖，
 * 直接引用它们的类会在未安装时触发 {@code NoClassDefFoundError} 崩溃，因此本类只按
 * {@link BuiltInRegistries} 的资源 id 取物品 / 流体，并用 {@link ModList} 判断加载状态
 * （{@code ModList} 只用于「这个模组到底在不在」这类诊断，绝不用于引用它的任何类型）。
 * 未安装（或注册表里查不到）时统一返回 {@link ItemStack#EMPTY} / {@link FluidStack#EMPTY}，
 * 由调用方静默走别的分支，不会产生任何“未安装 XX”的提示。
 * <p>
 * <b>本轮修复（用户：「安装了附魔工业但还是点不了这个按钮」）</b>：此前
 * {@link #enchantmentIndustryExperienceFluid(int)} 把 {@code ModList.get().isLoaded(modid)}
 * 当成<b>硬前置</b>——只要这一句返回 false，即使注册表里明明白白躺着
 * {@code create_enchantment_industry:experience}，探测也报「液态经验不可用」，
 * 界面于是把「液态经验」置灰。这既与 {@link XpTargetResolver} 的契约
 * （「注册表里是否真的存在」才是唯一判定标准）自相矛盾，也让探测对
 * 「modid 之外的任何原因」（装载顺序、同名 fork、id 变动）全部误判为不可用。
 * 现在<b>注册表为准</b>：拿到流体 / 物品就算可用；{@link ModList} 退居安全判据与诊断，
 * 只在「模组在、但这两个 id 一个都查不到」时打一条一次性 WARN，把缺失原因说清楚。
 * <p>
 * <b>经验换算出处</b>（均取自本仓库 local_src 下的上游源码）：
 * <ul>
 *     <li>附魔工业：1 mB 液态经验 = 1 经验点
 *     —— {@code CreateEnchantmentIndustry/.../common/fluids/experience/ExperienceHelper.java:63-65}
 *     （{@code fluid.is(CEIFluids.EXPERIENCE)} 时直接返回 {@code fluid.getAmount()}）
 *     与 :81-83（{@code getExperienceFluidUnit(EXPERIENCE)} 返回 1）；
 *     另见 {@code common/registry/CEIDataMaps.java:148}（经验桶 ExperienceFuel.normal(1000)）。</li>
 *     <li>附魔工业的液态经验流体 id = {@code create_enchantment_industry:experience}
 *     —— {@code CEIFluids.java:42-45}（{@code REGISTRATE.asResource("experience")}）。</li>
 *     <li>附魔工业「超越经验颗粒」（{@code create_enchantment_industry:super_experience_nugget}）：
 *     3 经验点 / 个 —— {@code CEIDataMaps.java:152}（{@code ExperienceFuel.special(3)}）。</li>
 *     <li>机械动力原版「经验颗粒」（{@code create:experience_nugget}）：3 经验点 / 个
 *     —— {@code CEIDataMaps.java:154}（{@code ExperienceFuel.normal(3)}），
 *     与 {@code Create/.../content/materials/ExperienceNuggetItem.java:37-38}
 *     （{@code total = Mth.ceil(3f * amountUsed)}）一致。</li>
 * </ul>
 */
public final class OptionalDeps {
    /**
     * 附魔工业 mod id。
     * <p><b>证据</b>：附魔工业自己的 {@code gradle.properties} 里 {@code mod_id = create_enchantment_industry}
     * （本仓库 {@code local_src/external/CreateEnchantmentIndustry/gradle.properties}），
     * 其资源命名空间同名（{@code cei_files.txt} 里成片的 {@code assets/create_enchantment_industry/…}），
     * 语言文件里也自称「机械动力：附魔工业」。此前代码用的就是这个字面量（不是错的 modid）；
     * 真正让界面「点了没反应」的是 {@link XpForm#byOrdinal(int)} 的序号映射，见那里的注释。</p>
     */
    public static final String MOD_CREATE_ENCHANTMENT_INDUSTRY = "create_enchantment_industry";
    /** Mekanism mod id。 */
    public static final String MOD_MEKANISM = "mekanism";
    /** 机械动力 mod id（必装前置，此处仅用于取原版经验颗粒 id）。 */
    public static final String MOD_CREATE = "create";
    /** Curios（饰品栏）mod id —— 已声明为必装前置，终端可放进饰品槽。 */
    public static final String MOD_CURIOS = "curios";
    /** FTB Ultimine（连锁采矿）mod id —— 可选依赖，只用于「连锁套壳」。 */
    public static final String MOD_FTB_ULTIMINE = "ftbultimine";

    /** 附魔工业液态经验（流体）id：ExperienceHelper.java:65 的 CEIFluids.EXPERIENCE。 */
    private static final ResourceLocation CEI_EXPERIENCE_FLUID =
        ResourceLocation.fromNamespaceAndPath(MOD_CREATE_ENCHANTMENT_INDUSTRY, "experience");
    /** 附魔工业经验颗粒（物品）id：CEIItems.SUPER_EXPERIENCE_NUGGET。 */
    private static final ResourceLocation CEI_EXPERIENCE_NUGGET =
        ResourceLocation.fromNamespaceAndPath(MOD_CREATE_ENCHANTMENT_INDUSTRY, "super_experience_nugget");
    /** 机械动力原版经验颗粒（物品）id：AllItems.EXP_NUGGET。 */
    private static final ResourceLocation CREATE_EXPERIENCE_NUGGET =
        ResourceLocation.fromNamespaceAndPath(MOD_CREATE, "experience_nugget");

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** 「模组在、id 查不到」的一次性诊断去重（进程内，按 id）。 */
    private static final java.util.Set<String> MISSING_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 附魔工业：每 1 mB 液态经验 = 1 经验点（ExperienceHelper.java:63-65、81-83）。 */
    public static final int CEI_EXPERIENCE_POINTS_PER_MB = 1;
    /** 附魔工业：每 1 经验点 = 1 mB 液态经验（同上，CEI 的换算比例为 1:1）。 */
    public static final int CEI_MB_PER_EXPERIENCE_POINT = 1;
    /** 附魔工业「超越经验颗粒」：3 经验点 / 个（CEIDataMaps.java:152）。 */
    public static final int CEI_EXPERIENCE_POINTS_PER_NUGGET = 3;
    /** 机械动力原版「经验颗粒」：3 经验点 / 个（CEIDataMaps.java:154、ExperienceNuggetItem.java:37-38）。 */
    public static final int CREATE_EXPERIENCE_POINTS_PER_NUGGET = 3;

    private OptionalDeps() {
    }

    /**
     * 附魔工业是否已加载（<b>安全判据与诊断用</b>，不是资源可用性的判据）。
     * <p>只问加载器「这个 modid 在不在」，绝不因此去 {@code Class.forName} 或引用它的类；
     * {@code ModList.get()} 在极早期可能为 null，这里判空后返回 false（优雅降级，不抛异常）。</p>
     */
    public static boolean isEnchantmentIndustryLoaded() {
        return ModList.get() != null && ModList.get().isLoaded(MOD_CREATE_ENCHANTMENT_INDUSTRY);
    }

    /** Mekanism 是否已加载（气体资源的可用性判断）。 */
    public static boolean isMekanismLoaded() {
        return ModList.get() != null && ModList.get().isLoaded(MOD_MEKANISM);
    }

    /** Curios（饰品栏）是否已加载（本模组已声明为必装前置，此判断只用于「缺失时静默降级」）。 */
    public static boolean isCuriosLoaded() {
        return ModList.get() != null && ModList.get().isLoaded(MOD_CURIOS);
    }

    /**
     * FTB Ultimine（连锁采矿）是否已加载。
     *
     * <p><b>为什么必须在碰它的 API 之前先问这一句</b>：分隔框架的「连锁套壳」整条链都挂在
     * FTB Ultimine 的右键连锁 API 上（{@link RsccUltimineIntegration} 只用<b>反射</b>访问它，
     * 编译期不引用任何 {@code dev.ftb.mods.*} / {@code dev.architectury.*} 类型）。
     * 即便如此，反射的入口也必须先被这句挡住：没装 FTB Ultimine 时我们连
     * {@code Class.forName} 都不会去调，自然不存在「解析不到类」的异常路径，
     * 本模组照常加载、行为就是原来的单格套壳。</p>
     */
    public static boolean isFtbUltimineLoaded() {
        return ModList.get() != null && ModList.get().isLoaded(MOD_FTB_ULTIMINE);
    }

    /**
     * 附魔工业的液态经验（流体）。
     *
     * <p><b>判定口径（本轮修复）</b>：注册表里存在 {@code create_enchantment_industry:experience}
     * 就是可用 —— 不再要求 {@code ModList.isLoaded(modid)} 同时为真。原因见类注释：
     * 拿 modid 当硬前置会把「注册表里明明有」的流体误判成不可用，界面于是把「液态经验」置灰，
     * 玩家看到的就是「装了附魔工业却还是点不了这个按钮」。</p>
     *
     * @param amountMb 数量（mB，1 mB = 1 经验点）
     * @return 注册表无此流体 / 数量非正 → {@link FluidStack#EMPTY}
     */
    public static FluidStack enchantmentIndustryExperienceFluid(final int amountMb) {
        if (amountMb <= 0) {
            return FluidStack.EMPTY;
        }
        final FluidStack stack = fluidStack(CEI_EXPERIENCE_FLUID, amountMb);
        if (stack.isEmpty()) {
            // 缺失侧诊断（一次性）：模组在、id 却查不到 —— 把「到底缺什么」写进日志，
            // 否则玩家只能看到按钮置灰，无法自查。绝不因此抛异常或改变任何行为。
            warnMissingOnce("流体 " + CEI_EXPERIENCE_FLUID);
        }
        return stack;
    }

    /**
     * 附魔工业的液态经验流体是否可用（注册表里确实存在该流体）。
     * <p>用于「装了附魔工业 → 让玩家选择流体 / 颗粒」的分支判断；缺失时静默返回 false，
     * 调用方（{@link XpForm#selectable()}）据此置灰并给出「缺哪个前置」的 tooltip。</p>
     */
    public static boolean hasEnchantmentIndustryExperienceFluid() {
        return !enchantmentIndustryExperienceFluid(1).isEmpty();
    }

    /**
     * 附魔工业的经验颗粒（物品 {@code super_experience_nugget}）。
     * <p>与流体侧同一口径：注册表为准，modid 只作诊断。</p>
     *
     * @param count 个数
     * @return 注册表无此物品 / 个数非正 → {@link ItemStack#EMPTY}
     */
    public static ItemStack enchantmentIndustryExperienceNugget(final int count) {
        if (count <= 0) {
            return ItemStack.EMPTY;
        }
        final ItemStack stack = itemStack(CEI_EXPERIENCE_NUGGET, count);
        if (stack.isEmpty()) {
            warnMissingOnce("物品 " + CEI_EXPERIENCE_NUGGET);
        }
        return stack;
    }

    /**
     * 缺失侧一次性诊断：只在「附魔工业确实已加载」却查不到对应 id 时打一条 WARN，
     * 每个 id 只打一次（进程内），避免每 tick 在探测路径上刷屏。
     * <p>模组压根没装时不打任何日志 —— 那是正常的可选依赖缺失，行为就是静默降级。</p>
     */
    private static void warnMissingOnce(final String what) {
        if (!isEnchantmentIndustryLoaded() || !MISSING_LOGGED.add(what)) {
            return;
        }
        LOGGER.warn("[rs_create_compat] 检测到「机械动力：附魔工业」({}) 已加载，但注册表里查不到 {} —— "
                + "液态经验相关的功能会退化为经验颗粒。若该模组版本改过资源 id，请反馈该 id。",
            MOD_CREATE_ENCHANTMENT_INDUSTRY, what);
    }

    /**
     * 机械动力原版的「经验颗粒」（物品 {@code create:experience_nugget}），
     * 用于没装附魔工业时的回退。
     *
     * @param count 个数
     * @return 注册表无此物品 / 个数非正 → {@link ItemStack#EMPTY}
     */
    public static ItemStack createExperienceNugget(final int count) {
        if (count <= 0) {
            return ItemStack.EMPTY;
        }
        return itemStack(CREATE_EXPERIENCE_NUGGET, count);
    }

    /**
     * 经验颗粒的统一入口：装了附魔工业用其「超越经验颗粒」，否则回退机械动力原版经验颗粒。
     *
     * @param count 个数
     * @return 两者都取不到 → {@link ItemStack#EMPTY}
     */
    public static ItemStack experienceNugget(final int count) {
        final ItemStack cei = enchantmentIndustryExperienceNugget(count);
        return cei.isEmpty() ? createExperienceNugget(count) : cei;
    }

    /** 经验点 → 附魔工业液态经验 mB（比例为 1:1，见 {@link #CEI_MB_PER_EXPERIENCE_POINT}）。 */
    public static int experiencePointsToEnchantmentIndustryFluidMb(final int points) {
        return Math.max(0, points) * CEI_MB_PER_EXPERIENCE_POINT;
    }

    /** 附魔工业液态经验 mB → 经验点（比例为 1:1，见 {@link #CEI_EXPERIENCE_POINTS_PER_MB}）。 */
    public static int enchantmentIndustryFluidMbToExperiencePoints(final int mb) {
        return Math.max(0, mb) * CEI_EXPERIENCE_POINTS_PER_MB;
    }

    /** 按 id 取物品并建栈（id 不存在时注册表返回 {@code air}，这里再转成 EMPTY）。 */
    private static ItemStack itemStack(final ResourceLocation id, final int count) {
        final Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item, count);
    }

    /** 按 id 取流体并建栈（id 不存在时注册表返回 {@code empty} 流体，这里再转成 EMPTY）。 */
    private static FluidStack fluidStack(final ResourceLocation id, final int amount) {
        final Fluid fluid = BuiltInRegistries.FLUID.get(id);
        if (fluid == null || fluid == Fluids.EMPTY) {
            return FluidStack.EMPTY;
        }
        return new FluidStack(fluid, amount);
    }
}
