package cretae.cookiewyq.rs_create_compat.client.grid;

import com.refinedmods.refinedstorage.common.api.grid.GridResourceAttributeKeys;
import com.refinedmods.refinedstorage.common.api.grid.view.GridResource;
import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 让精致存储（RS）网格搜索框的<b>裸文本</b>也能搜到物品的 tooltip 文字 —— 用户需求的原话是
 * 「我想搜索水下呼吸这个附魔书，我直接搜索水下呼吸，JEI 是能搜到的，但精致存储里不具备这个功能」。
 *
 * <h2>一、事实核查：RS 2.0.9 到底缺什么</h2>
 * <p>RS 2.0.9 的网格搜索<b>不是</b>「只能按名字搜」，它有一套查询语法（{@code GridQueryParser}，
 * 语法字符由 {@code LexerTokenMappings.DEFAULT_MAPPINGS} 定义）：</p>
 * <ul>
 *     <li>{@code #某tag} —— 按物品 / 方块标签的 <b>path</b> 搜（{@code GridResourceAttributeKeys.TAGS}）；</li>
 *     <li>{@code @某模组id} —— 按模组 id / 显示的模组名搜（{@code MOD_ID} / {@code MOD_NAME}）；</li>
 *     <li>{@code $某文字} —— 按 <b>tooltip 全文</b>搜（{@code TOOLTIP}）；</li>
 *     <li>还支持 {@code &&} / {@code ||} / {@code !} / 括号 / 计数过滤（{@code >5} 之类）。</li>
 * </ul>
 * <p>所以「附魔书按附魔名搜不到」的真实原因<b>不是</b>「RS 没有 tooltip 数据」——它连 tooltip 全文都采好了
 * （{@code AbstractItemGridResourceRepositoryMapper} 用
 * {@code ItemStack#getTooltipLines(..., TooltipFlag.ADVANCED)} 生成）。真正缺的只有一条：
 * <b>裸文本</b>走的 {@code parseLiteral} 只比对 {@code GridResource#getSearchableNames()}
 * （物品名 + 自定义名），<b>从不看 tooltip</b>。玩家必须知道并手写 {@code $水下呼吸} 才搜得到。</p>
 * <p>「没人知道」也是事实：RS 的搜索框左边确实有一个问号帮助图标，里面有一行
 * {@code gui.refinedstorage.grid.search_help.tooltip_search = "Use '$' to filter for resources
 * with a specific tooltip"}；但这行字在 RS 自带的 {@code zh_cn.json} 里<b>没有翻译</b>（仍是英文），
 * 而且它是「前缀语法」而不是「直接搜就搜得到」—— 用户（中文客户端）既看不到中文说明，也不会去猜
 * 一个美元符号。因此本类的意义不是「RS 完全没提示」，而是<b>把「要记语法才做得到」变成
 * 「像 JEI 那样直接打字就做得到」</b>。</p>
 * <p>因此本类要补的语义只有一句：<b>裸文本 = 名字 <i>或</i> tooltip</b>。{@code $} / {@code #} / {@code @}
 * 的语义一个字都不动（它们走 {@code parseUnaryOp} → {@code attributeMatch}，根本不经过
 * {@code parseLiteral}，注入点选在那里就是为了不碰它们）。</p>
 *
 * <h2>二、配置开关（默认开；第 95 轮按用户要求补上）</h2>
 * <p>第 94 轮用户说「这个不需要配置，应该也没有人会去关闭它」，于是当时<b>没有开关</b>；
 * 第 95 轮用户改口：「那既然这玩意是默认存在的还是把它搞成可配置的吧」。因此现在有且只有
 * <b>一个</b>开关 {@code Config.gridSearchMatchesTooltip}（默认 {@code true}）：</p>
 * <ul>
 *     <li><b>唯一读点</b>在 {@link #matches} 的入口（自检在源码层钉住「只有这一处」）——
 *     关掉后一个 tooltip 都不会被生成，裸文本立刻退回 RS 原版的「只比名字」语义；</li>
 *     <li>{@link #textMatches(String, String)} 仍然是<b>纯函数</b>，绝不读配置、绝不碰 Minecraft ——
 *     自检靠它把「中文子串 / 大小写 / 空值」这些语义在没有游戏的机器上真跑一遍；</li>
 *     <li>默认值必须是 {@code true}：默认关闭等于这个功能不存在（用户要的是「可关」，不是「默认没有」）。</li>
 * </ul>
 *
 * <h2>三、为什么要有本缓存（而不是直接用 RS 的属性）</h2>
 * <p>RS 的 mapper 给每个资源配的 tooltip 取值器是<b>「每个 {@code GridResource} 实例一份」</b>：
 * 物品是 {@code Suppliers.memoize(...)}（首次读属性才算），流体是 {@code Suppliers.ofInstance(...)}
 * （创建时就算）。{@code GridResource} 实例在同一个 repository 内由
 * {@code CachingResourceRepositoryMapper} 缓存（资源数量变化时复用，不会每次更新都重建），
 * <b>但 repository 是每个菜单新建的</b>（{@code AbstractGridContainerMenu} 的构造器里建仓库）
 * ⇒ 「关掉界面再开」= 整张网络的 {@code GridResource} 全部重建 = <b>tooltip 全部重算</b>。
 * 裸文本搜索让每一次搜索都走这条路（而不是只在玩家主动写 {@code $} 时），于是
 * 「每开一次网格、第一次敲字就把网络里所有物品的 tooltip 生成一遍」会变成常态 ——
 * 在网络有几千种物品时，这就是一次看得见的卡顿（RS 自己的 {@code $} 搜索也有这笔开销）。
 * 本缓存把它摊平：按 <b>资源键</b>（{@link PlatformResourceKey}，值语义，外加资源类型）缓存
 * 「小写 + 行拼接」后的 tooltip 全文，<b>跨界面、跨仓库重建、跨 tick 复用</b>，
 * 于是同一个物品一次会话只算一次。</p>
 *
 * <h2>四、缓存的六条边界（都不许省）</h2>
 * <ol>
 *     <li><b>键带资源类型</b>：{@code (GridResource.class, PlatformResourceKey)}。RS 允许附属模组
 *     通过 {@code RefinedStorageApi#addGridResourceRepositoryMapper} 换掉 mapper，只按资源键缓存
 *     等于假设「同一个资源键在所有实现下 tooltip 相同」；把类型放进去就不必做这个假设。</li>
 *     <li><b>条数 + 字符总量双上限</b>：{@link #MAX_CACHED_RESOURCES} 条 LRU，外加
 *     {@link #MAX_CACHED_CHARS} 的总字符预算（超了整表清空）。没有上限的缓存就是内存泄漏 ——
 *     玩家换存档、换整合包、逛服务器商店都会往里塞新物品。</li>
 *     <li><b>语言 / 世界变了要清空</b>：tooltip 是<b>翻译后</b>的字符串（{@code Component#getString()}
 *     按当前语言解析），缓存里存的是「某个语言下的文本」；而 tooltip 里还可能有依赖当前
 *     level（维度相关模组）的行。所以语言代码变化或 {@code Minecraft#level} 换了对象都要整表作废，
 *     否则中文客户端会拿英文 tooltip 去比中文关键词，表现为「切了语言就搜不到了」。</li>
 *     <li><b>超长的不缓存</b>：个别物品（成书、模组说明物品）tooltip 可能上万字符；这类条目
 *     只「现算现用」，不进缓存。</li>
 *     <li><b>失败不缓存</b>：某个模组的 tooltip 生成抛异常时，RS 自己会吞掉并记一条 warn
 *     （{@code AbstractItemGridResourceRepositoryMapper#getTooltip}）。本类在<b>读属性</b>这一步
 *     也包一层 {@code Throwable}，但 <b>不把失败结果写进缓存</b> —— 否则一次瞬态异常会让该物品
 *     在这一整局里永远搜不到。</li>
 *     <li><b>缓存自身的容器操作也要兜底</b>：键是外部实现（{@code PlatformResourceKey}）的对象，
 *     其 {@code hashCode/equals} 万一抛异常，不能让搜索框跟着炸。</li>
 * </ol>
 *
 * <h2>五、匹配口径（与 RS 自己的 {@code $} 完全一致，不另起一套）</h2>
 * <p>RS 的 {@code attributeMatch} 用的是 {@code value.trim().toLowerCase(Locale.ROOT).contains(...)}；
 * 本类逐字照抄同一个 {@link #normalize(String)}，因此：</p>
 * <ul>
 *     <li>「水下呼吸」能命中 tooltip 行「水下呼吸 III」（子串匹配，不要求整行相等）；</li>
 *     <li>英文大小写不敏感（{@code Locale.ROOT}，不受土耳其语 i 之类的区域设置影响）；</li>
 *     <li>能搜到的文本与 {@code $} 完全同一份：因为 RS 用的是 {@code TooltipFlag.ADVANCED}，
 *     所以除了正常 tooltip，<b>还包含 F3+H 才看得到的进阶行</b>（物品注册 id、耐久、组件个数）。
 *     好处是英文玩家可以顺手用物品 id 搜；代价见下一条。</li>
 * </ul>
 * <p><b>与 RS 唯一可见的语义差异是 {@code !}（否定）</b>：RS 的 {@code !foo} 是
 * {@code not(parseLiteral(foo))}，而本类把叶子换成「名字或 tooltip」之后，{@code !foo} 的含义变成
 * <b>「名字与 tooltip 都不含 foo」</b>。例如中文客户端里 {@code !minecraft} 以前几乎筛不掉任何东西
 * （物品名是中文），现在会把所有原版物品藏起来（它们的进阶 tooltip 行里有 {@code minecraft:xxx}）。
 * 这是「裸文本 = 搜遍所有文本」这一口径的必然结果，<b>不是 bug</b>；CHANGELOG 与本类注释都如实写明，
 * 而不是假装「组合语义一点没变」。</p>
 *
 * <h2>六、已知代价（如实记录，不粉饰）</h2>
 * <p>第一次搜索时，凡是名字没命中的物品都要生成一次 tooltip（之后命中缓存；语言 / 世界变化后
 * 再生成一次）。这笔开销与 RS 的 {@code $} 搜索同源，而且发生在<b>客户端主线程</b>上
 * （过滤在界面 tick 里跑）—— 几千种物品的网络首搜可能有肉眼可见的顿挫。本类只把它从
 * 「每次开界面重算」降成「一次会话一次」；如果哪天要再优化，正确方向是<b>后台线程预热索引</b>
 * （JEI 的做法），而不是把功能关掉。</p>
 */
public final class RsccGridTooltipSearch {
    private static final Logger LOGGER = LoggerFactory.getLogger("rscc-grid-search");

    /** 缓存条数上限（LRU）。一个超大型网络的「物品 + 流体种类」量级在数千。 */
    private static final int MAX_CACHED_RESOURCES = 4096;

    /** 单条 tooltip 文本允许进缓存的最大字符数：超过的条目仍参与匹配（现算现用），只是不占缓存。 */
    private static final int MAX_CACHED_TEXT_LENGTH = 4096;

    /**
     * 全部缓存文本的字符总量预算（UTF-16 ⇒ 正文最坏约 4 MB）。超出时整表清空重来 ——
     * 比「按条数 × 单条上限」更能兜住「一屋子成书」这种极端情况。
     */
    private static final long MAX_CACHED_CHARS = 2_000_000L;

    /**
     * 把多行 tooltip 拼成一条<b>搜索索引</b>时的行分隔符：U+0001（SOH）。
     *
     * <p><b>真实原因</b>（不要误读成「RS 会传多行」）：RS 给 TOOLTIP 属性传的是一个
     * <b>单元素集合</b>，元素内部已经用换行拼好了（物品是 {@code getTooltipLines} 逐行 join，
     * 流体是单个名字），所以对 RS 自带资源来说这个分隔符<b>永远不会出现</b>。之所以仍然用一个
     * 控制字符而不是换行字面量，是因为本工程有一条硬规则
     * （{@code tools/selfcheck_round69_tooltip_stack.py}）：「{@code client/} 与 {@code item/} 的
     * Java 代码行里不得出现换行字面量」—— 那条规则针对的是<b>渲染文本</b>（行高按单行算，
     * 多行塞进一个 Component 会压行），而这里拼的是<b>搜索索引</b>、一个字都不会画到屏幕上。
     * 与其给规则开一个例外（例外就是下一个漏洞的入口），不如换一个绝不可能与任何显示文字或
     * 玩家查询冲突的字符；U+0001 也顺便保证「即使将来某个 mapper 真的传多行，也不会跨行假命中」。</p>
     */
    private static final String LINE_SEPARATOR = "\u0001";

    /** 键 = (资源类型, 资源键)；值 = 小写后的 tooltip 全文。访问序 LRU。 */
    private static final Map<CacheKey, String> TOOLTIP_TEXT = new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<CacheKey, String> eldest) {
            if (size() > MAX_CACHED_RESOURCES) {
                cachedChars -= eldest.getValue().length();
                return true;
            }
            return false;
        }
    };

    /** 已缓存文本的字符总数（与 {@link #TOOLTIP_TEXT} 同源维护，用于 {@link #MAX_CACHED_CHARS} 预算）。 */
    private static long cachedChars;

    /** 上一份缓存所属的语言代码；{@code null} = 还不知道（下次调用会写入）。 */
    private static String cachedLanguage;

    /** 上一份缓存所属的客户端世界对象（身份比较；换维度 / 换存档都会换对象）。 */
    private static Level cachedLevel;

    /** 已经 warn 过的资源类型（同一类型只记一次，避免「每次搜索刷一屏」）。 */
    private static final Set<Class<?>> WARNED_FAILURES = new HashSet<>();

    private RsccGridTooltipSearch() {
    }

    /**
     * 裸文本是否命中该资源的 tooltip 全文。
     *
     * <p>调用点只有一个：{@code GridQueryParserTooltipSearchMixin} 在 {@code parseLiteral} 返回后
     * 把「名字匹配」的判定与本方法做<b>逻辑或</b>。顺序很重要：名字先判、tooltip 后判，
     * 于是<b>名字已经命中的物品根本不会去生成 tooltip</b>（短路），这是最省的那一半优化。</p>
     *
     * @param resource 网格里的一个资源（物品 / 流体 / 其它接入 RS 的资源类型）
     * @param literal  玩家在搜索框里写的<b>裸文本字面量</b>（不含 {@code $}/{@code #}/{@code @} 前缀）
     * @return true = 该字面量出现在 tooltip 里
     */
    public static boolean matches(final GridResource resource, final String literal) {
        if (resource == null) {
            return false;
        }
        // 配置开关（Config.gridSearchMatchesTooltip，默认 true）：关掉后这里立刻返回 false，
        // 于是裸文本退回 RS 原版的「只比名字」语义，且一个 tooltip 都不会被生成。
        // 读点是本方法的唯一入口 ⇒ 开关只需要一处；`$` / `#` / `@` 不经过这里，因此不受影响。
        if (!cretae.cookiewyq.rs_create_compat.Config.gridSearchMatchesTooltip) {
            return false;
        }
        return textMatches(tooltipText(resource), literal);
    }

    /**
     * <b>纯字符串</b>判定：tooltip 全文里是否含这个关键词（不做任何 Minecraft 调用）。
     *
     * <p>抽成 public 纯函数有两个实际用处：① {@link #matches} 只剩「取文本 + 转调」两行，
     * 判定口径只有一处；② 自检可以在<b>没有游戏</b>的机器上直接对<b>真代码</b>跑断言
     * （{@code tools/selfcheck_round94_grid_tooltip_search.py} 会真的编译并执行它），
     * 于是「中文子串能命中」「大小写不敏感」「空关键词不算命中」这些语义不是靠读源码相信的，
     * 而是跑出来看过的。</p>
     *
     * @param tooltipText 已拼接好的 tooltip 全文（本类内部传入的是小写版；本方法自己也会再规范化一遍，
     *                    因此外部直接传原文也正确 —— 规范化是幂等的）
     * @param query       玩家写的裸文本
     */
    public static boolean textMatches(final String tooltipText, final String query) {
        final String needle = normalize(query);
        if (needle.isEmpty() || tooltipText == null) {
            // 空关键词不该「匹配一切」：RS 对空查询本来就有自己的语义（返回恒真过滤器），
            // 走到这里说明查询里有一个空字面量，保守地判不命中。
            return false;
        }
        return normalize(tooltipText).contains(needle);
    }

    /** 取该资源的小写 tooltip 全文（命中缓存则直接用；读不到返回 {@code null}）。 */
    private static String tooltipText(final GridResource resource) {
        refreshContext();
        final CacheKey key = cacheKey(resource);
        if (key != null) {
            try {
                final String cached = TOOLTIP_TEXT.get(key);
                if (cached != null) {
                    return cached;
                }
            } catch (final Throwable t) {
                // 外部实现的 hashCode/equals 抛异常：退化为「不缓存」，本次照常现算。
                return readTooltip(resource);
            }
        }
        final String text = readTooltip(resource);
        if (key != null && text != null) {
            remember(key, text);
        }
        return text;
    }

    /** 把一条成功的 tooltip 文本放进缓存（含字符预算与 LRU 维护）。 */
    private static void remember(final CacheKey key, final String text) {
        if (text.length() > MAX_CACHED_TEXT_LENGTH) {
            return;
        }
        try {
            if (cachedChars + text.length() > MAX_CACHED_CHARS) {
                // 预算爆了：整表清空重来（比逐条淘汰更容易与 LRU 的 removeEldestEntry 配合，且此路径极罕见）。
                TOOLTIP_TEXT.clear();
                cachedChars = 0L;
            }
            final String previous = TOOLTIP_TEXT.put(key, text);
            if (previous != null) {
                cachedChars -= previous.length();
            }
            cachedChars += text.length();
        } catch (final Throwable t) {
            // 缓存本身失败不影响搜索结果：本次已经算出文本，直接返回即可。
        }
    }

    /** 缓存键：资源类型 + RS 给食谱模组用的资源键（值语义）；拿不到则返回 {@code null}（不缓存）。 */
    private static CacheKey cacheKey(final GridResource resource) {
        try {
            final PlatformResourceKey platform = resource.getResourceForRecipeMods();
            return platform == null ? null : new CacheKey(resource.getClass(), platform);
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * 从 RS 的属性表里读 tooltip 全文；失败返回 {@code null}（<b>失败不缓存</b>，下次还有机会成功）。
     *
     * <p>注意这里<b>不自己生成 tooltip</b>：物品 / 流体的 tooltip 采集口径（{@code TooltipFlag.ADVANCED}、
     * 玩家 / 世界上下文、异常兜底）全部留在 RS 的 {@code AbstractItemGridResourceRepositoryMapper} /
     * {@code AbstractFluidGridResourceRepositoryMapper} 里，本类只消费
     * {@link GridResourceAttributeKeys#TOOLTIP} 这一个属性。这样 RS 以后改采集口径（比如加入
     * 更多行），本类自动跟着变，不会出现「两份 tooltip 生成逻辑对不上」。</p>
     */
    private static String readTooltip(final GridResource resource) {
        try {
            final Set<String> lines = resource.getAttribute(GridResourceAttributeKeys.TOOLTIP);
            if (lines == null || lines.isEmpty()) {
                // 属性存在但为空：这是「确定的空」，可以缓存（返回空串，不命中任何关键词）。
                return "";
            }
            return normalize(String.join(LINE_SEPARATOR, lines));
        } catch (final Throwable t) {
            warnOnce(resource.getClass(), t);
            return null;
        }
    }

    /** 同一资源类型只 warn 一次；并且绝不让日志本身再抛（自定义 toString 也可能抛）。 */
    private static void warnOnce(final Class<?> type, final Throwable error) {
        try {
            if (WARNED_FAILURES.add(type)) {
                LOGGER.warn("[rscc-grid-search] 读取 {} 的 tooltip 失败（同一类型只记一次），"
                    + "本次及其后续失败按不匹配处理", type == null ? "?" : type.getName(), error);
            }
        } catch (final Throwable ignored) {
            // 日志系统本身出问题也不影响搜索。
        }
    }

    /** 与 RS {@code GridQueryParser#normalize} 逐字一致：去首尾空白 + {@code Locale.ROOT} 小写。 */
    private static String normalize(final String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 语言或世界变化则整表作废。
     *
     * <p>为什么每次调用都检查而不是挂事件：{@code Minecraft#getLanguageManager().getSelected()}
     * 与 {@code Minecraft#level} 都只是一次字段读 + 一次引用 / 字符串比较，比注册
     * {@code ResourceManagerReloadListener} 与断连事件少两个注册点，也不会漏掉「别的模组改了语言」
     * 这类非标准路径。切语言 / 换维度本身都会重建界面，因此这里只需要保证<b>缓存</b>不跨语言、
     * 不跨世界复用。</p>
     */
    private static void refreshContext() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        final String language = minecraft.getLanguageManager().getSelected();
        final Level level = minecraft.level;
        if (!language.equals(cachedLanguage) || level != cachedLevel) {
            TOOLTIP_TEXT.clear();
            cachedChars = 0L;
            cachedLanguage = language;
            cachedLevel = level;
        }
    }

    /** 缓存键：(网格资源实现类, 资源键)。放在最后，避免打断上面的常量区可读性。 */
    private record CacheKey(Class<? extends GridResource> type, PlatformResourceKey resource) {
    }
}
