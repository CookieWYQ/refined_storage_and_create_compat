package cretae.cookiewyq.rs_create_compat.client;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 「通用拼音搜索」（Just Enough Characters / {@code jecharacters}）的<b>软依赖</b>接入点。
 *
 * <h2>要解决的问题（用户第 110 条反馈）</h2>
 * <p>玩家装了拼音搜索模组，在创造模式背包 / JEI 里可以直接敲 {@code kongzhiqi} 找到「控制器」，
 * 但在本模组<b>范围充电器的过滤搜索框</b>里敲拼音什么都搜不到 —— 因为我们的搜索是
 * 「显示名 / 注册 id 的 {@code contains} 子串」自己实现的（{@link
 * cretae.cookiewyq.rs_create_compat.client.screen.ChargerFilterCandidates#search(String)}），
 * 而原版 {@code SearchTree}/{@code SuffixArray} 才是拼音模组的注入点，我们的手写匹配完全绕开了它。</p>
 *
 * <h2>为什么走反射（而不是 compileOnly 依赖）</h2>
 * <p>本工程的编译校验（{@code tools/manual_compile.ps1} / {@code tools/_lead_compile.ps1}）
 * 只用<b>本地 Gradle 缓存里的 jar</b> 拼 classpath；拼音模组的 jar 只在玩家的
 * {@code .minecraft/versions/.../mods} 里，没有进 Maven / Gradle 缓存。为了一个可选的客户端模组
 * 去改整套离线编译链路不值得，而且「玩家没装拼音模组」也必须是完全正常的一等状态。
 * 因此这里：<b>零编译期依赖</b>，运行时用 {@link Class#forName} + {@link Method} 反射调用；
 * 找不到 / 调用失败 / 版本不兼容 ⇒ <b>静默退回普通子串匹配</b>（行为与没有本类时逐字相同）。</p>
 *
 * <h2>为什么调 {@code Match.contains} 而不是自己实现拼音</h2>
 * <p>反编译 {@code jecharacters-1.21.1-neoforge-4.5.29.jar} 确认它已经有完整的字典 / 多音字 /
 * 简拼支持：{@code me.towdium.jecharacters.utils.Match.contains(CharSequence, CharSequence)}
 * 内部就是 {@code me.towdium.pinin.PinIn.contains(text, query)}。玩家在配置里改的
 * 模糊音（zh↔z、sh↔s、ang↔an、v↔ü 等）也由这个对象统一生效 —— 自己再写一份拼音表
 * 既不可能与模组的配置一致，也会把「玩家已经调好的习惯」再一次弄坏。</p>
 *
 * <h2>失败时的降级（绝不能把搜索弄挂）</h2>
 * <p>每次调用都 {@code try/catch Throwable}：拼音模组缺席、类名在别的版本里改了、
 * 字典还没加载完、反射被模块系统拒绝 …… 任一情况都退回 {@code String.contains}，
 * 而不是让一次搜索抛异常把界面弄崩。解析结果（类 / 方法）缓存为静态，只反射一次。</p>
 *
 * <h2>线程 / 侧别</h2>
 * <p>只被客户端搜索逻辑调用（{@code client} 包），专用服务端永远不会加载本类；
 * 方法本身无状态（除了解析缓存），可在客户端主线程安全调用。</p>
 */
public final class PinyinSearch {
    /** 拼音模组暴露的匹配入口：{@code public static boolean contains(CharSequence, CharSequence)}。 */
    private static final String MATCH_CLASS = "me.towdium.jecharacters.utils.Match";

    /** 已经解析过（无论成功还是失败，只解析一次）。 */
    private static boolean resolved;
    /** 解析成功时的 {@code Match.contains}；失败为 {@code null}。 */
    private static Method matchContains;

    private PinyinSearch() {
    }

    /**
     * 文本是否「命中」查询串。<b>拼音模组存在时按拼音规则</b>（含简拼 / 模糊音），
     * 否则退回不区分大小写的普通子串匹配。
     *
     * @param text  被搜索的文本（通常是物品 / 方块的本地化显示名）
     * @param query 玩家输入的查询串（可能是拼音、简拼或汉字）
     */
    public static boolean contains(final String text, final String query) {
        if (text == null || query == null || query.isEmpty()) {
            return true;
        }
        // 【重要】JEC 的 PinIn/Match.contains 对 ASCII 是**大小写敏感**的
        // （实测: Match.contains("Controller","controller") = false,
        //  Match.contains("控制器","KZQ") = false；两边都 lower 后才 true）。
        // 若不先 lower 就传进去，会出现「装了拼音模组后英文名/大写首字母反而搜不到」的行为回归
        // （没装时我们的子串 fallback 是大小写不敏感的）。中文不受 lower 影响。
        final String haystack = text.toLowerCase(Locale.ROOT);
        final String needle = query.toLowerCase(Locale.ROOT);
        final Method method = matchContains();
        if (method != null) {
            try {
                return Boolean.TRUE.equals(method.invoke(null, haystack, needle));
            } catch (final Throwable ignored) {
                // 反射失败（参数类型 / 模组内部异常 / 字典未就绪）⇒ 按降级路径继续，绝不抛出。
            }
        }
        return haystack.contains(needle);
    }

    /** 当前是否真的接上了拼音模组（仅用于界面提示 / 自检 / 排障，不参与判定）。 */
    public static boolean pinyinAvailable() {
        return matchContains() != null;
    }

    /**
     * 解析拼音模组的入口方法（只解析一次；失败也记下来，不会每次搜索都反射一遍）。
     * <p>依次尝试上下文类加载器与 {@code PinyinSearch} 自己的类加载器：NeoForge 下所有模组通常
     * 在同一个转换类加载器里，但「谁先加载」并不保证，两条路都试一遍最稳。</p>
     */
    private static synchronized Method matchContains() {
        if (resolved) {
            return matchContains;
        }
        resolved = true;
        Class<?> type = null;
        for (final ClassLoader loader : new ClassLoader[] {
            Thread.currentThread().getContextClassLoader(),
            PinyinSearch.class.getClassLoader()
        }) {
            if (loader == null) {
                continue;
            }
            try {
                type = Class.forName(MATCH_CLASS, false, loader);
                break;
            } catch (final Throwable ignored) {
                // 没装拼音模组 / 不是这个类加载器：继续试下一个。
            }
        }
        if (type == null) {
            return null;
        }
        // 签名按“更贴近真实调用点”的顺序依次尝试：JEC 4.5.29 同时有 (String, CharSequence) 与
        // (CharSequence, CharSequence)；再留一个 (String, String) 兜底，兼容更老/更新版本。
        // 记录时我们是 public class + public static，不需要 setAccessible。
        for (final Class<?>[] signature : new Class<?>[][] {
            {String.class, CharSequence.class},
            {CharSequence.class, CharSequence.class},
            {String.class, String.class}
        }) {
            try {
                matchContains = type.getMethod("contains", signature);
                break;
            } catch (final Throwable ignored) {
                // 这个签名不存在：继续试下一个。
            }
        }
        return matchContains;
    }
}
