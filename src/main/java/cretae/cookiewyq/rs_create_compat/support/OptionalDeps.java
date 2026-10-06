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
 * <b>为什么全部走「模组加载判断 + 注册表按 id 查找」</b>：附魔工业 / Mekanism 都是可选依赖，
 * 直接引用它们的类会在未安装时触发 {@code NoClassDefFoundError} 崩溃，因此本类只用
 * {@link ModList} 判断加载状态，再用 {@link BuiltInRegistries} 按资源 id 取物品 / 流体。
 * 未安装（或注册表里查不到）时统一返回 {@link ItemStack#EMPTY} / {@link FluidStack#EMPTY}，
 * 由调用方静默走别的分支，不会产生任何“未安装 XX”的提示。
 * <p>
 * <b>经验换算出处</b>（均取自本仓库 local_src 下的上游源码）：
 * <ul>
 *     <li>附魔工业：1 mB 液态经验 = 1 经验点
 *     —— {@code CreateEnchantmentIndustry/.../common/fluids/experience/ExperienceHelper.java:63-65}
 *     （{@code fluid.is(CEIFluids.EXPERIENCE)} 时直接返回 {@code fluid.getAmount()}）
 *     与 :81-83（{@code getExperienceFluidUnit(EXPERIENCE)} 返回 1）；
 *     另见 {@code common/registry/CEIDataMaps.java:148}（经验桶 ExperienceFuel.normal(1000)）。</li>
 *     <li>附魔工业「超越经验颗粒」（{@code create_enchantment_industry:super_experience_nugget}）：
 *     3 经验点 / 个 —— {@code CEIDataMaps.java:152}（{@code ExperienceFuel.special(3)}）。</li>
 *     <li>机械动力原版「经验颗粒」（{@code create:experience_nugget}）：3 经验点 / 个
 *     —— {@code CEIDataMaps.java:154}（{@code ExperienceFuel.normal(3)}），
 *     与 {@code Create/.../content/materials/ExperienceNuggetItem.java:37-38}
 *     （{@code total = Mth.ceil(3f * amountUsed)}）一致。</li>
 * </ul>
 */
public final class OptionalDeps {
    /** 附魔工业 mod id。 */
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

    /** 附魔工业是否已加载。 */
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
     * @param amountMb 数量（mB，1 mB = 1 经验点）
     * @return 未加载 / 注册表无此流体 / 数量非正 → {@link FluidStack#EMPTY}
     */
    public static FluidStack enchantmentIndustryExperienceFluid(final int amountMb) {
        if (amountMb <= 0 || !isEnchantmentIndustryLoaded()) {
            return FluidStack.EMPTY;
        }
        return fluidStack(CEI_EXPERIENCE_FLUID, amountMb);
    }

    /**
     * 附魔工业的液态经验流体是否可用（装了附魔工业且注册表里确实存在该流体）。
     * <p>用于「装了附魔工业 → 让玩家选择流体/颗粒」的分支判断。
     */
    public static boolean hasEnchantmentIndustryExperienceFluid() {
        return !enchantmentIndustryExperienceFluid(1).isEmpty();
    }

    /**
     * 附魔工业的经验颗粒（物品 {@code super_experience_nugget}）。
     *
     * @param count 个数
     * @return 未加载 / 注册表无此物品 / 个数非正 → {@link ItemStack#EMPTY}
     */
    public static ItemStack enchantmentIndustryExperienceNugget(final int count) {
        if (count <= 0 || !isEnchantmentIndustryLoaded()) {
            return ItemStack.EMPTY;
        }
        return itemStack(CEI_EXPERIENCE_NUGGET, count);
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
