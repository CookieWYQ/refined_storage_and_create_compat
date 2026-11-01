package cretae.cookiewyq.rs_create_compat.client.tooltip;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 「配方类型 → JEI 官方注册的催化剂（能跑该配方的机器）」反查（<b>只读</b>，客户端）。
 *
 * <h2>为什么需要它</h2>
 * <p>用户实机反馈：{@code createbigcannons:*} / {@code createaddition:*} 的配方类型在机器列表里
 * <b>一台机器都没有</b>。根因不在配方，而在<b>另外两条来源都拿不到</b>：</p>
 * <ol>
 *     <li>显式机器表只写了 Create + 原版（见 {@link RecipeTypeMachines}）；</li>
 *     <li>这两个模组的配方<b>一个都没有覆写</b> {@code Recipe#getToastSymbol()}（实测
 *     {@code createbigcannons-5.11.7+mc.1.21.1.jar} / {@code createaddition-1.7.2.jar} 里该方法出现 0 次），
 *     于是返回原版默认「工作台」，而 {@code symbolsFromRecipes} 对非 {@code minecraft:crafting} 类型会把
 *     这个默认值当「无信息」丢掉。</li>
 * </ol>
 * <p>但机器<b>客观存在且模组自己声明过</b>：CBC 的
 * {@code rbasamoyai/createbigcannons/compat/jei/CBCBlockRecipeCategory} 与 Create Addition 的
 * {@code com/mrh0/createaddition/compat/jei/CreateAdditionJEI} 都调用了
 * {@code IRecipeCatalystRegistration#addRecipeCatalyst(...)}。JEI 把这份声明保存在
 * {@code RecipeTypeData#getRecipeCategoryCatalysts()}，并通过
 * {@code IRecipeManager#createRecipeCatalystLookup(RecipeType)} 公开只读查询 —— 本类就是这条通用来源。</p>
 *
 * <h2>确切 API（已用 {@code javap} 对真实 jar 核对：19.27.0.336 与 19.52.0.421 的签名一致）</h2>
 * <pre>
 * mezz.jei.common.Internal#getJeiRuntime() : mezz.jei.api.runtime.IJeiRuntime      （静态；未就绪时抛 IllegalStateException）
 * mezz.jei.api.runtime.IJeiRuntime#getRecipeManager() : mezz.jei.api.recipe.IRecipeManager
 * mezz.jei.api.recipe.IRecipeManager#getRecipeType(ResourceLocation)
 *     : java.util.Optional&lt;mezz.jei.api.recipe.RecipeType&lt;?&gt;&gt;
 * mezz.jei.api.recipe.IRecipeManager#createRecipeCatalystLookup(mezz.jei.api.recipe.RecipeType&lt;?&gt;)
 *     : mezz.jei.api.recipe.IRecipeCatalystLookup
 * mezz.jei.api.recipe.IRecipeCatalystLookup#includeHidden() : IRecipeCatalystLookup
 * mezz.jei.api.recipe.IRecipeCatalystLookup#getItemStack() : java.util.stream.Stream&lt;ItemStack&gt;  （default 方法）
 * </pre>
 * <p><b>为什么先 {@code getRecipeType(id)} 再查催化剂</b>：{@code RecipeType.equals} 比较
 * {@code uid + recipeClass} 两项（{@code javap -c} 可见），第三方分类用的是它们自己的配方类，
 * 我们自己 {@code new RecipeType(...)} 出来的对象永远不相等。{@code getRecipeType(id)} 会按
 * uid 找回<b>注册时那个真实实例</b>，再交给 {@code createRecipeCatalystLookup} 才能命中。</p>
 * <p><b>为什么用 {@code includeHidden()}</b>：默认查询会按玩家的 JEI 物品隐藏 / 搜索过滤掉一部分催化剂；
 * 机器列表要的是「模组官方声明过什么」，不应随玩家的隐藏设置或搜索词变化，否则同一份缓存会时有时无。
 * 因此这里固定取「含隐藏」的完整注册表视图（仍然<b>只读</b>，绝不改 JEI 的任何注册）。</p>
 *
 * <h2>依赖安全：为什么整个类没有一个 JEI import</h2>
 * <p>JEI 在本工程是<b>可选</b>依赖（{@code build.gradle} 里只有 {@code compileOnly}/{@code runtimeOnly}）。
 * 只要主路径类在<b>类加载期</b>引用了 JEI 类型，未安装时就会在解析阶段抛
 * {@link NoClassDefFoundError}，而那是 {@link Error} 不是 {@link Exception} —— 专用服务端会直接启动失败
 * （本工程为这类写法崩过）。因此本类照抄工程既有的隔离手法（见
 * {@code support/RsccUltimineIntegration} / {@code support/RsccWrenchDismantle}）：</p>
 * <ul>
 *     <li><b>先看模组在不在</b>：{@link ModList#get()} + {@code isLoaded("jei")}。没装 → 一句反射都不做；</li>
 *     <li><b>所有 JEI 类名只以字符串出现</b>：{@code Class.forName(..., false, loader)} 懒加载，
 *     拿到 {@link Method} 后再 invoke；字段 / 方法签名 / 返回值类型<b>没有一个是 JEI 类型</b>；</li>
 *     <li><b>整个入口包住</b> {@code ReflectiveOperationException | RuntimeException | LinkageError}：
 *     API 改名 / 缺类 / 专用服务端上 JEI 内部类解析失败（含 {@link NoClassDefFoundError}）都只会让
 *     本来源返回空列表，安静退回既有的两条来源，<b>不抛异常、不打日志</b>；</li>
 *     <li><b>失败只判定一次</b>：{@code Class.forName} / {@code getMethod} 失败记为永久降级
 *     （模组加载后不会中途多出类）；「runtime 还没就绪」是<b>正常状态</b>，不记为失败，下次照常重试。</li>
 * </ul>
 *
 * <h2>缓存（性能 + 自愈）</h2>
 * <p>tooltip / 界面每帧都会问「这个配方类型有哪些机器」，绝不能每次全量扫 JEI 注册表。本类按</p>
 * <ul>
 *     <li><b>非空结果</b>：按 {@code IJeiRuntime} 实例身份缓存在 {@link #CACHE}，命中后只做一次
 *     {@code getJeiRuntime()} 取代际（没有配方扫描）；</li>
 *     <li><b>空结果</b>：只缓存 {@link #EMPTY_RETRY_NANOS} 这么短的时间（负缓存），且带代际 ——
 *     JEI 初始化完成 / 资源重载后 runtime 会换成<b>新实例</b>（{@code JeiStarter#stop} 先
 *     {@code Internal.setRuntime(null)}，重启再 set 新对象），代际一变立刻重查，不需要任何事件监听；</li>
 *     <li><b>上限</b>：两张表都封顶，避免第三方整合包注册出成千上万个配方类型时无限增长。</li>
 * </ul>
 * <p>这样既满足「空结果不能被永久缓存」的自愈要求，也不会在 JEI 尚未就绪时每帧都去扫一遍注册表。</p>
 *
 * <p><b>只读</b>：本类只调用 {@code getRecipeType} / {@code createRecipeCatalystLookup} /
 * {@code includeHidden} / {@code getItemStack}，不调用任何 {@code hide*} / {@code add*} / {@code unHide*}。</p>
 */
public final class JeiCatalystMachines {
    /** JEI 的 mod id（只用于 {@link ModList} 探测，不代表任何 JEI 类型）。 */
    private static final String MOD_ID = "jei";
    /** 取 runtime 的入口（JEI 内部类；只以字符串出现）。 */
    private static final String INTERNAL_CLASS = "mezz.jei.common.Internal";
    /** JEI runtime 接口：取 recipeManager 走接口，避免实现类包私有 / 改名。 */
    private static final String RUNTIME_CLASS = "mezz.jei.api.runtime.IJeiRuntime";
    /** 配方管理器接口（按接口取方法，避开实现类可能包私有的问题）。 */
    private static final String RECIPE_MANAGER_CLASS = "mezz.jei.api.recipe.IRecipeManager";
    /** 催化剂查询接口（含 default 方法 {@code getItemStack()}）。 */
    private static final String CATALYST_LOOKUP_CLASS = "mezz.jei.api.recipe.IRecipeCatalystLookup";
    /** JEI 的配方类型（{@code getRecipeType} / {@code createRecipeCatalystLookup} 的形参类型）。 */
    private static final String RECIPE_TYPE_CLASS = "mezz.jei.api.recipe.RecipeType";

    /** 空结果的负缓存时长：既要避免每帧重扫，又要让 JEI 就绪 / 资源重载后很快自愈。 */
    private static final long EMPTY_RETRY_NANOS = 1_000_000_000L; // 1 秒
    /** 单个配方类型最多取多少个催化剂（最终还会被 {@link RecipeTypeMachines#MAX_MACHINES} 截断）。 */
    private static final int MAX_CATALYSTS = 16;
    /** 缓存上限（配方类型数量有限，这里只是防御性封顶）。 */
    private static final int CACHE_LIMIT = 512;

    /** 永久降级：JEI 没装（加载器层面就不可能变化，判一次即可）。 */
    private static volatile boolean jeiAbsent;
    /** 永久降级：JEI 装了但反射入口不存在 / 类解析失败（API 改名、专用服务端上的 dist 问题）。 */
    private static volatile Throwable reflectionFailure;
    /** 缓存的 {@code Internal.getJeiRuntime()}（成功解析后复用）。 */
    private static volatile Method runtimeAccessor;

    /** 缓存当前归属的 {@code IJeiRuntime} 实例（= 代际；换实例即整批作废）。 */
    private static volatile Object cacheGeneration;

    /** 非空结果：键 = 配方类型 id（如 {@code createaddition:charging}）。 */
    private static final Map<String, List<ItemStack>> CACHE = new ConcurrentHashMap<>();
    /** 空结果的短 TTL 负缓存。 */
    private static final Map<String, EmptyMark> EMPTY = new ConcurrentHashMap<>();
    /** 两张缓存共用的锁（只在缓存写 / 代际切换时进入，读路径基本无锁）。 */
    private static final Object LOCK = new Object();

    /** 一条空结果的记录：代际 + 记录时间（纳秒）。 */
    private record EmptyMark(Object generation, long atNanos) {
    }

    private JeiCatalystMachines() {
    }

    /**
     * 当前 JEI 代际（{@code IJeiRuntime} 实例；未安装 / 尚未就绪 → {@code null}）。
     *
     * <p>供 {@link RecipeTypeMachines} 的负缓存使用：代际一变（JEI 第一次就绪、资源重载重启 JEI）
     * 就让「空结果」立刻重查，而不是等满 TTL —— 这是「不自愈」的根治点之一。</p>
     */
    public static Object generation() {
        return currentRuntime();
    }

    /**
     * 查这个配方类型在 JEI 里注册的催化剂（机器方块 / 物品）。
     *
     * <p><b>降级契约</b>：JEI 未安装、API 反射失败、runtime 未就绪、JEI 里没有这个配方类型
     * → 一律返回空列表；<b>绝不抛异常、绝不打日志</b>。调用方（{@link RecipeTypeMachines}）
     * 会安静地继续用显式表 / {@code getToastSymbol()} 两条来源。</p>
     *
     * @param recipeTypeId 配方类型 id（如 {@code create:pressing}）
     * @return 不可变列表（元素已 copy 成数量 1；可能为空）
     */
    public static List<ItemStack> catalystsFor(final String recipeTypeId) {
        if (recipeTypeId == null || recipeTypeId.isEmpty()) {
            return List.of();
        }
        final Object runtime = currentRuntime();
        if (runtime == null) {
            // 未安装 / 未就绪：不碰缓存，直接空（安装与否不会中途变化；就绪后下一次调用自然能查到）
            return List.of();
        }
        if (runtime != cacheGeneration) {
            synchronized (LOCK) {
                if (runtime != cacheGeneration) {
                    CACHE.clear();
                    EMPTY.clear();
                    cacheGeneration = runtime;
                }
            }
        }
        final List<ItemStack> cached = CACHE.get(recipeTypeId);
        if (cached != null) {
            return cached;
        }
        final EmptyMark empty = EMPTY.get(recipeTypeId);
        if (empty != null && empty.generation() == runtime
            && System.nanoTime() - empty.atNanos() < EMPTY_RETRY_NANOS) {
            return List.of(); // 刚才查过、确实没有：短 TTL 内不重复扫
        }
        final List<ItemStack> result = query(runtime, recipeTypeId);
        if (result.isEmpty()) {
            if (EMPTY.size() < CACHE_LIMIT) {
                EMPTY.put(recipeTypeId, new EmptyMark(runtime, System.nanoTime()));
            }
            return List.of();
        }
        EMPTY.remove(recipeTypeId);
        if (CACHE.size() < CACHE_LIMIT) {
            CACHE.put(recipeTypeId, result);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 反射：取 runtime
    // ------------------------------------------------------------------

    /**
     * 当前 JEI runtime；未安装 / 未就绪 / 反射失败时返回 {@code null}。
     *
     * <p><b>为什么「未就绪」不算失败</b>：JEI 在资源重载 / 进世界时才会
     * {@code Internal.setRuntime(...)}，在那之前 {@code getJeiRuntime()} 会抛
     * {@link IllegalStateException}（Guava {@code Preconditions}）。这是正常的启动顺序，
     * 若把它当成永久失败，就再也不会重试 —— 正是用户看到的「一直空」。</p>
     */
    private static Object currentRuntime() {
        if (jeiAbsent) {
            return null;
        }
        try {
            if (ModList.get() == null) {
                return null; // 加载器尚未初始化：这是“还不知道”，不能当成“没装”永久降级
            }
            if (!ModList.get().isLoaded(MOD_ID)) {
                jeiAbsent = true; // 加载器已就绪且确实没装：后面不必再问
                return null;
            }
        } catch (final RuntimeException | LinkageError e) {
            return null;
        }
        final Method accessor = runtimeAccessor();
        if (accessor == null) {
            return null;
        }
        try {
            return accessor.invoke(null);
        } catch (final InvocationTargetException e) {
            // runtime 还没 set：正常状态，下次继续试（不记失败、不打日志）
            return null;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            // 调用期才暴露的 dist / 初始化问题：安静降级，不再重试
            reflectionFailure = e;
            return null;
        }
    }

    /** 解析并缓存 {@code Internal.getJeiRuntime()}；失败后永久降级（返回 null）。 */
    private static Method runtimeAccessor() {
        final Method cached = runtimeAccessor;
        if (cached != null) {
            return cached;
        }
        if (reflectionFailure != null) {
            return null;
        }
        synchronized (LOCK) {
            if (runtimeAccessor != null) {
                return runtimeAccessor;
            }
            if (reflectionFailure != null) {
                return null;
            }
            try {
                final Class<?> internal = Class.forName(INTERNAL_CLASS, false,
                    JeiCatalystMachines.class.getClassLoader());
                final Method method = internal.getMethod("getJeiRuntime");
                runtimeAccessor = method;
                return method;
            } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
                reflectionFailure = e;
                return null;
            }
        }
    }

    // ------------------------------------------------------------------
    // 反射：查催化剂
    // ------------------------------------------------------------------

    /**
     * 真正的 JEI 查询（只在缓存未命中时执行一次）。
     *
     * <p>全部通过 {@code IRecipeManager} / {@code IRecipeCatalystLookup} 两个<b>接口</b>取方法：
     * JEI 的实现类（{@code RecipeManagerInternal}）可能变化 / 包私有，而接口是稳定的公开 API。</p>
     */
    private static List<ItemStack> query(final Object runtime, final String recipeTypeId) {
        try {
            final ResourceLocation id = ResourceLocation.tryParse(recipeTypeId);
            if (id == null) {
                return List.of();
            }
            final ClassLoader loader = JeiCatalystMachines.class.getClassLoader();
            final Class<?> runtimeClass = Class.forName(RUNTIME_CLASS, false, loader);
            final Class<?> managerClass = Class.forName(RECIPE_MANAGER_CLASS, false, loader);
            final Class<?> recipeTypeClass = Class.forName(RECIPE_TYPE_CLASS, false, loader);
            final Class<?> lookupClass = Class.forName(CATALYST_LOOKUP_CLASS, false, loader);

            final Object manager = runtimeClass.getMethod("getRecipeManager").invoke(runtime);
            if (manager == null) {
                return List.of();
            }
            final Object found = managerClass.getMethod("getRecipeType", ResourceLocation.class)
                .invoke(manager, id);
            if (!(found instanceof final Optional<?> optional) || optional.isEmpty()) {
                return List.of(); // JEI 里没有这个配方类型（第三方可能只在自行分类里展示）
            }
            final Object lookup = managerClass
                .getMethod("createRecipeCatalystLookup", recipeTypeClass)
                .invoke(manager, optional.get());
            if (lookup == null) {
                return List.of();
            }
            // includeHidden：不受玩家 JEI 物品隐藏 / 搜索过滤影响（见类注释）
            final Object visibleLookup = lookupClass.getMethod("includeHidden").invoke(lookup);
            final Object streamObject = lookupClass.getMethod("getItemStack").invoke(visibleLookup);
            if (!(streamObject instanceof final Stream<?> stream)) {
                return List.of();
            }
            final List<ItemStack> catalysts = new ArrayList<>(4);
            try (stream) {
                stream.forEach(candidate -> {
                    if (catalysts.size() >= MAX_CATALYSTS
                        || !(candidate instanceof final ItemStack stack)
                        || stack.isEmpty()) {
                        return;
                    }
                    catalysts.add(stack.copyWithCount(1));
                });
            }
            return catalysts.isEmpty() ? List.of() : List.copyOf(catalysts);
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // 任何一条 API 不匹配都只让本来源为空：显式表 / toast symbol 继续工作（安静降级）
            return List.of();
        }
    }
}
