package cretae.cookiewyq.rs_create_compat.data;

import net.minecraft.ChatFormatting;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配方类型显示名解析工具（所有 GUI / 提示共用，避免各写一份键查找逻辑）。
 *
 * <h2>为什么需要「多级回退」（第 78 轮用户实机反馈）</h2>
 * <p>用户截图里的「配方类型」菜单<b>中英混杂</b>：{@code create:pressing} 显示「冲压」（Create 自己的
 * 语言文件有 {@code create.recipe.pressing}），而 {@code createbigcannons:big_cartridge_filling}
 * 却显示英文。原因不是我们的解析写错了，而是<b>它自己的 jar 里根本没有这个键</b>：
 * Create Big Cannons 的 {@code zh_cn.json} 只有 {@code recipe.createbigcannons.melting} 等 5 个键，
 * 它的 6 个组装/装填/引信类配方类型（{@code big_cartridge_filling} …）没有翻译
 * （证据：{@code tools/selfcheck_round78_recipe_type_l10n.py} 的 jar 扫描段）。</p>
 * <p>所以「能不能本地化别的模组的配方类型」只靠查它自己的语言文件是<b>做不到</b>的；
 * 必须再加一张<b>我们自己的词典</b>（键前缀见 {@link #DICT_PREFIX}）兜住这些缺口，
 * 最后才退到「把 id 派生成人话」。</p>
 *
 * <h2>解析顺序（先命中先返回）</h2>
 * <ol>
 *     <li><b>我们自己的词典</b> {@code machine.rs_create_compat.recipe_type.<命名空间>.<路径>}
 *     —— 最高优先级：第三方没翻译 / 翻得不好时由我们补，且中英各写一份；</li>
 *     <li><b>旧的机器名键</b> {@code machine.rs_create_compat.<路径首字母大写>}
 *     （历史键，如 {@code create:pressing} → {@code machine.rs_create_compat.Pressing}）；</li>
 *     <li><b>该模组自己的官方翻译</b>（按下面的 {@link #THIRD_PARTY_KEYS} 逐个探测）：
 *     {@code create.recipe.pressing}（Create / Create Addition / 机械动力：火炮都用这种）、
 *     {@code recipe.<ns>.<path>}（一部分模组）、{@code <ns>.recipe_type.<path>}、
 *     {@code emi.category.<ns>.<path>} / {@code jei.category.<ns>.<path>}（有的模组只给分类名）；</li>
 *     <li><b>从注册 id 派生</b>：去掉命名空间 → 按 {@code _ - .} 切词 → 逐词查我们的单词表
 *     （{@link #WORD_PREFIX}，中英各一份）→ 按语言拼接（中文不加空格、英文加空格）；</li>
 *     <li><b>最终兜底</b>：原样显示 id（绝不留空白、绝不抛异常）。</li>
 * </ol>
 *
 * <h2>性能</h2>
 * <p>解析结果全部进 {@link #CACHE}（{@link ConcurrentHashMap}），同一个 id 只做一次字典/语言表查找；
 * 切换语言时 {@link Language} 会被换成<b>新实例</b>（{@code Language.inject}），
 * 因此缓存用「语言表实例」做版本标记 —— 实例一变整张缓存作废，绝不会把中文名留到英文界面。
 * 每次调用只有「volatile 读 + 一次 identity 比较 + 一次 map 读」，可以放心每帧调。</p>
 * <p><b>专服安全</b>：只用通用类 {@link Language}（非客户端专属的 {@code I18n}），
 * 因此在服务端（候选列表排序）调用不会抛 {@code NoClassDefFoundError}；
 * 服务端的语言表就是 {@code en_us}，于是排序用的是英文名，与客户端语言无关。</p>
 */
public final class RecipeTypeNames {
    /** 原始 id 行的前缀文案（如 {@code [id] create:pressing}）。 */
    private static final String LANG_ID_LABEL = "gui.rs_create_compat.recipe_type.id_label";
    /** id 为空 / 未设置时的占位灰字。 */
    private static final String LANG_ID_UNSET = "gui.rs_create_compat.recipe_type.id_unset";

    /**
     * 我们自己的配方类型词典前缀：{@code machine.rs_create_compat.recipe_type.<命名空间>.<路径>}。
     * <p>放在 {@code machine.} 头段下是本工程既有约定（{@code machine.rs_create_compat.Pressing} 一类旧键），
     * {@code tools/audit_lang_keys.py} 也把 {@code machine.rs_create_compat.} 当作动态前缀，
     * 因此这批键不会被误报成「孤儿键」。</p>
     */
    private static final String DICT_PREFIX = "machine.rs_create_compat.recipe_type.";
    /** 旧的机器名键前缀（保留，避免老存档 / 老界面文案突然变成英文）。 */
    private static final String LEGACY_PREFIX = "machine.rs_create_compat.";
    /** 派生用的单词表前缀：{@code machine.rs_create_compat.word.<小写英文词>}。 */
    private static final String WORD_PREFIX = "machine.rs_create_compat.word.";

    /**
     * 第三方模组常用的「配方类型名」键格式（按优先级；{@code %1$s} = 命名空间，{@code %2$s} = 路径）。
     * <p>顺序有讲究：{@code <ns>.recipe.<path>} 是 Create 系（Create 6.0.10 的 47 个
     * {@code create.recipe.*} 键、Create Addition 的 {@code createaddition.recipe.charging}、
     * 机械动力：火炮的 {@code recipe.createbigcannons.melting} 都属这一类），先用它。</p>
     */
    private static final String[] THIRD_PARTY_KEYS = {
        "%1$s.recipe.%2$s",
        "recipe.%1$s.%2$s",
        "%1$s.recipe_type.%2$s",
        "emi.category.%1$s.%2$s",
        "jei.category.%1$s.%2$s"
    };

    /** 解析结果缓存上限（配方类型数量是有限的，这里只是防御性封顶）。 */
    private static final int CACHE_LIMIT = 4096;

    /**
     * 官方英文名与 id 词不同的少数类型（<b>英文搜索别名</b>，2026-10-17 第 84 轮）。
     *
     * <p><b>为什么需要它</b>：搜索要求「按 id / 中文名 / 英文名」三者都能命中。中文名与当前语言
     * 的名字都在 {@link Language} 里，id 词也在 id 里；但<b>官方英文名偶尔含有 id 里根本没有的词</b>
     * （Create 的 {@code create:emptying} 官方英文是 {@code Item Draining}、{@code create:sequenced_assembly}
     * 是 {@code Recipe Sequence}，机械动力：火炮的 {@code melting} 是 {@code Basin Melting}）。
     * 那些值在<b>中文客户端上拿不到</b>（{@code Language} 是 en_us 打底 + 当前语言覆盖，中文键把英文盖掉了），
     * 所以补这一张小表：值全部取自各模组 jar 的 {@code en_us.json}（见
     * {@code tools/selfcheck_round78_recipe_type_l10n.py} 的 ⑩ 段，有 jar 时会逐条硬核对）。</p>
     *
     * <p><b>为什么只放「多出来的词」</b>：id 词已经天然进搜索串（{@code emptying} 能搜到
     * {@code create:emptying}），这里只补那些「英文名里有、id 里没有」的词（draining / sequence / basin …），
     * 表越小越不容易过时。</p>
     */
    private static final Map<String, String> EN_SEARCH_ALIASES = Map.of(
        // Create 6.0.10：create.recipe.draining = Item Draining
        "create:emptying", "Item Draining",
        // Create 6.0.10：create.recipe.item_application = Manual Item Application
        "create:item_application", "Manual Item Application",
        // Create 6.0.10：create.recipe.sequenced_assembly = Recipe Sequence
        "create:sequenced_assembly", "Recipe Sequence",
        // 机械动力：火炮 5.11.7：recipe.createbigcannons.melting = Basin Melting
        "createbigcannons:melting", "Basin Melting",
        // 机械动力：火炮 5.11.7：recipe.createbigcannons.built_up_heating = Cannon Building
        "createbigcannons:built_up_heating", "Cannon Building"
    );

    /**
     * 语言值里的格式化占位符（{@code %s} / {@code %1$s} / {@code %d}）。
     * <p>为什么要挡：模组的 {@code recipe.<ns>.<path>} 命名空间里常混着**提示句**
     * （如 {@code recipe.createbigcannons.casting_time} = 「Casting time: %ss」）。
     * 万一某个配方类型的路径刚好撞上这种键，直接把值当名字就会在界面里显示 {@code %s}；
     * 见到占位符就跳过、继续走后面的回退，比显示半句模板安全。</p>
     */
    private static final java.util.regex.Pattern PLACEHOLDER =
        java.util.regex.Pattern.compile("%(?:\\d+\\$)?[sd%]");

    /**
     * 缓存 + 它所属的语言表实例（语言表换了实例 ⇒ 整张缓存作废）。
     * <p>{@code searches} 是<b>搜索串</b>缓存（id + 折叠后的 id + 当前语言显示名 + 英文别名），
     * 与 {@code names} 同生命周期：换语言后「中文名」那一截必须跟着变，否则中文玩家切回英文后
     * 还会用中文名命中（或反过来）。</p>
     */
    private record Cache(Language owner, Map<String, String> names, Map<String, String> searches) {
    }

    private static volatile Cache cache;

    private RecipeTypeNames() {
    }

    /** 解析配方类型显示名（多级回退；空串返回空串）。 */
    public static String of(final String recipeType) {
        if (recipeType == null || recipeType.isEmpty()) {
            return "";
        }
        final Cache holder = cache();
        final String hit = holder.names().get(recipeType);
        if (hit != null) {
            return hit;
        }
        final String resolved = resolve(holder.owner(), recipeType);
        if (holder.names().size() < CACHE_LIMIT) {
            holder.names().put(recipeType, resolved);
        }
        return resolved;
    }

    /** 显示名（可见文字用）：即 {@link #of(String)} 的 Component 包装，空串返回空文字。 */
    public static Component display(final String recipeType) {
        return Component.literal(of(recipeType));
    }

    /**
     * 原始配方类型 id 的一行 tooltip：固定 {@link ChatFormatting#DARK_GRAY} + {@link ChatFormatting#ITALIC}，
     * 前缀 {@code [id]}（语言键），形如 {@code [id] create:pressing}。
     * <p>id 为空 / 未设置时返回「未设置」一类灰字（同样走语言键），便于调用方统一追加。</p>
     */
    public static Component idLine(final String recipeType) {
        if (recipeType == null || recipeType.isEmpty()) {
            return Component.translatable(LANG_ID_UNSET)
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);
        }
        return Component.translatable(LANG_ID_LABEL, Component.literal(recipeType))
            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);
    }

    // ------------------------------------------------------------------
    // 搜索（第 84 轮：配方类型选择器支持「按 id / 中文名 / 英文名」过滤）
    // ------------------------------------------------------------------

    /**
     * 搜索串（小写、各段以空格分隔）：<b>同一个配方类型的三种键都装在一条串里</b>，
     * 因此界面侧只需要一次 {@code contains}，不需要为「按 id / 按中文 / 按英文」各写一套比较。
     *
     * <p><b>串里有什么</b>（顺序固定，便于自检复算）：</p>
     * <ol>
     *     <li>原始 id 小写（{@code createbigcannons:big_cartridge_filling}）—— 支持直接粘 id；</li>
     *     <li>折叠后的 id（{@code : _ - .} 全部换成空格并合并连续空格）—— 支持输入
     *     {@code big cartridge} / {@code create pressing}；</li>
     *     <li>当前语言的显示名（中文客户端 = 中文名、英文客户端 = 官方英文名，<b>派生名也在内</b>，
     *     因此「冷冻」「大型弹药装填」这类由我们派生出来的名字同样可搜）；</li>
     *     <li>英文别名（{@link #EN_SEARCH_ALIASES}，只补「官方英文名里有、id 词里没有」的少数词）。</li>
     * </ol>
     *
     * <p><b>性能</b>：每个 id 只拼一次并进 {@link Cache#searches()} 缓存（与显示名同一份语言表版本），
     * 过滤循环里每项成本 = 一次 {@code String.contains}。切语言时整张缓存随语言表实例一起作废，
     * 因此不会把中文名留到英文界面。</p>
     *
     * <p><b>专服安全</b>：只用 {@link Language}，与 {@link #of(String)} 完全同一条依赖路径。</p>
     */
    public static String searchText(final String recipeType) {
        if (recipeType == null || recipeType.isEmpty()) {
            return "";
        }
        final Cache holder = cache();
        final String hit = holder.searches().get(recipeType);
        if (hit != null) {
            return hit;
        }
        final String built = buildSearchText(recipeType);
        if (holder.searches().size() < CACHE_LIMIT) {
            holder.searches().put(recipeType, built);
        }
        return built;
    }

    /**
     * 配方类型是否命中搜索词（<b>大小写不敏感</b>，空查询恒命中）。
     *
     * <p>规则与 {@link #searchText(String)} 的串一一对应：查询先小写 + 去首尾空白，先按原样匹配
     * （保留 {@code : _ - .} 的写法，例如 {@code create:pressing} / {@code big_cartridge}），
     * 再按「分隔符折叠成空格」的形态匹配一次（例如 {@code big cartridge}）。
     * 三种键（id / 中文名 / 英文名）都装在搜索串里，所以这里不需要知道用户打的是哪一种。</p>
     */
    public static boolean matchesSearch(final String recipeType, final String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        final String needle = query.trim().toLowerCase(Locale.ROOT);
        final String haystack = searchText(recipeType);
        if (haystack.contains(needle)) {
            return true;
        }
        final String folded = foldSeparators(needle);
        return !folded.equals(needle) && haystack.contains(folded);
    }

    /** 拼一条搜索串（只在缓存未命中时执行一次）。 */
    private static String buildSearchText(final String recipeType) {
        final StringBuilder out = new StringBuilder(recipeType.length() * 3 + 16);
        out.append(recipeType.toLowerCase(Locale.ROOT)).append(' ');
        out.append(foldSeparators(recipeType)).append(' ');
        out.append(foldSeparators(of(recipeType))).append(' ');
        final String alias = EN_SEARCH_ALIASES.get(recipeType);
        if (alias != null) {
            out.append(foldSeparators(alias));
        }
        // 统一小写：英文别名 / 英文显示名是带大写的（"Item Draining"），查询侧也一律小写，
        // 因此这里必须整串折成小写再进缓存（否则英文名永远搜不到）。
        return out.toString().toLowerCase(Locale.ROOT).trim();
    }

    /**
     * 把 {@code : _ - .} 换成空格并压掉多余空白（<b>唯一的折叠实现</b>，搜索串与查询共用）。
     * <p>不改变大小写、不动中文 —— 调用方自行决定是否先 {@code toLowerCase}。</p>
     */
    private static String foldSeparators(final String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        final StringBuilder out = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            final boolean separator = c == ':' || c == '_' || c == '-' || c == '.';
            if (separator || Character.isWhitespace(c)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.append(c);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // 解析实现
    // ------------------------------------------------------------------

    /** 真正的多级回退（只在缓存未命中时执行）。 */
    private static String resolve(final Language language, final String recipeType) {
        final int colon = recipeType.indexOf(':');
        final String namespace = colon < 0 ? "minecraft" : recipeType.substring(0, colon);
        final String path = colon < 0 ? recipeType : recipeType.substring(colon + 1);

        // ① 我们自己的词典：中英各写一份，能覆盖「第三方没翻译」的缺口。
        String found = lookup(language, DICT_PREFIX + namespace + "." + path);
        // ② 旧的机器名键（历史兼容）。
        if (found == null) {
            found = lookup(language, LEGACY_PREFIX + capitalize(path));
        }
        // ③ 该模组自己的官方翻译（多种常见键格式，按优先级）。
        for (int i = 0; found == null && i < THIRD_PARTY_KEYS.length; i++) {
            found = lookup(language, THIRD_PARTY_KEYS[i].formatted(namespace, path));
        }
        if (found != null) {
            return found;
        }
        // ④ 从 id 派生可读名（中文走单词表，英文至少能读）。
        final String derived = derive(language, path);
        // ⑤ 最终兜底：原样 id（绝不留空白）。
        return derived.isEmpty() ? recipeType : derived;
    }

    /** 取一个语言键的值；键不存在、值为空白、或值是带占位符的提示句时返回 {@code null}（继续回退）。 */
    private static String lookup(final Language language, final String key) {
        if (!language.has(key)) {
            return null;
        }
        final String value = language.getOrDefault(key, "");
        if (value == null || value.isBlank() || PLACEHOLDER.matcher(value).find()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 从注册 id 的路径派生可读名。
     *
     * <p><b>算法</b>（可复跑，见 {@code tools/selfcheck_round78_recipe_type_l10n.py}）：</p>
     * <ol>
     *     <li>按 {@code _} / {@code -} / {@code .} 切词，丢掉空串；</li>
     *     <li>逐词查单词表 {@link #WORD_PREFIX}{@code <词>}：命中就用译文，未命中就用「首字母大写」的英文词；</li>
     *     <li>拼接规则按<b>字形</b>而不是「当前语言代码」：
     *     两个词都是中文 ⇒ 直接粘（中文习惯）；词是英文 ⇒ 前后要有空格（英文习惯）；
     *     中英交界处补一个空格（{@code built_up_heating} ⇒ {@code Built Up 加热}，
     *     而不是 {@code BuiltUp加热}）。这样中英两套语言各得到正确形态；</li>
     *     <li>没有任何词时返回空串（调用方回退原始 id）。</li>
     * </ol>
     * <p>幂等：输出里不含 {@code _ - .}，再喂回来只会被「首字母大写」一次，结果不变。</p>
     */
    private static String derive(final Language language, final String path) {
        final String[] words = path.split("[_\\-.]+");
        final StringBuilder out = new StringBuilder();
        boolean previousCjk = false;
        int added = 0;
        for (final String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            final String translated = lookup(language, WORD_PREFIX + word.toLowerCase(Locale.ROOT));
            final String part = translated != null ? translated : capitalize(word);
            final boolean cjk = containsCjk(part);
            // 英文词之间 / 中英交界都要空格；只有「连续的纯中文」才紧贴。
            if (added > 0 && (!cjk || !previousCjk)) {
                out.append(' ');
            }
            out.append(part);
            previousCjk = cjk;
            added++;
        }
        return out.toString();
    }

    /** 是否含中日韩文字（用来决定中文「不加空格」的拼接方式）。 */
    private static boolean containsCjk(final String text) {
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if ((c >= '\u4e00' && c <= '\u9fff')      // 中日韩统一表意文字
                || (c >= '\u3400' && c <= '\u4dbf')   // 扩展 A
                || (c >= '\u3000' && c <= '\u303f')   // 中文标点
                || (c >= '\uff00' && c <= '\uffef')) { // 全角字符
                return true;
            }
        }
        return false;
    }

    /** 首字母大写（用于 {@code machine.rs_create_compat.*} 这类旧键与派生词）。 */
    private static String capitalize(final String path) {
        if (path.isEmpty()) {
            return path;
        }
        return Character.toUpperCase(path.charAt(0)) + path.substring(1);
    }

    /**
     * 取当前语言表对应的缓存；语言表实例变了（客户端重载资源 / 换语言）就换一张新表。
     * <p>用「实例身份」而不是「内容指纹」：{@code ClientLanguage.loadFrom} 每次都 new 一个新实例，
     * 而这次查找本身必须是 O(1)，不能每帧扫语言表。</p>
     */
    private static Cache cache() {
        final Language current = Language.getInstance();
        Cache local = cache;
        if (local != null && local.owner() == current) {
            return local;
        }
        synchronized (RecipeTypeNames.class) {
            local = cache;
            if (local == null || local.owner() != current) {
                local = new Cache(current, new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
                cache = local;
            }
            return local;
        }
    }
}
