package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.data.RecipeTypeNames;
import cretae.cookiewyq.rs_create_compat.network.CompletionBannerPayload;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * 「服务端 → 客户端的可翻译文本」唯一编码/解码器。
 *
 * <p><b>为什么需要它（本轮本地化 bug 的根因）</b>：Minecraft 的语言表只存在于<b>客户端</b>——
 * 专用服务端上 {@code Language.getInstance()} 只有 {@code en_us}（见 {@code Language.loadDefault()}），
 * 客户端选中的语言是在客户端资源重载时 {@code Language.inject(clientLanguage)} 才注入的。
 * 因此任何在服务端执行 {@code Component#getString()}（或把 {@code Component} 压成 {@code String}
 * 再走网络）的名字，都会<b>在服务端被固化成英文</b>，客户端拿到的是死字符串、再也翻译不回来。
 *
 * <p>本工程的既有正解是「<b>只送语言键、客户端解析</b>」（{@link CompletionBannerPayload#localized}
 * 与其客户端解析 {@code client/CompatCompletionToast#resolve}）。本类把同一套协议收口成公共 API，
 * 供所有「必须经网络/存档传递的名字」共用，避免各处再写一遍编码规则。</p>
 *
 * <p><b>编码形状</b>：{@code \u0001<语言键>\u0002<参数1>\u0002<参数2>...}；不以标记开头的字符串
 * 视为「已经是玩家可见文本」，{@link #resolve(String)} 原样返回 —— 因此旧存档里的名字、
 * 玩家自定义名、RS 原版自己的名字（它们本来就已本地化或本来就该原样显示）都不受影响。</p>
 *
 * <p><b>嵌套参数（第 74 轮补：产物名 / 缺料名 / 配方名）</b>：参数本身也可能是一个「需要按玩家语言
 * 解析」的名字（例如把「铁锭」塞进「由于 %s 缺少」里）。这种参数用 {@link #argKey(String)}
 * 编码成 {@code \u0003<语言键>}，{@link #itemNameArg(ItemStack)} / {@link #fluidNameArg(FluidStack)}
 * 是它的两个现成入口；配方类型名用 {@link #argRecipeType(String)}（{@code \u0004<注册 id>}），
 * 由客户端用 {@link RecipeTypeNames} 按自己的语言解析。**绝不要**在服务端把名字
 * {@code getString()} 后直接当参数 —— 那正是「专服上横幅仍显示英文」的写法（见
 * {@code tools/selfcheck_round74_localization.py} 的判据 F：日志里可以这么写，玩家可见文本里不行）。</p>
 *
 * <p><b>dist 安全</b>：本类只用通用类 {@link Component} / {@link ItemStack} / {@link FluidStack}
 * 与通用数据类 {@link RecipeTypeNames}（不含任何 {@code net.minecraft.client.*}），
 * 因此可以同时被服务端方块实体与客户端 Mixin 引用。</p>
 */
public final class RsccClientText {
    /** 文本以它开头 ⇒ 后面是语言键（与完成横幅同一套标记，避免再定义第二种协议）。 */
    public static final char KEY_MARKER = CompletionBannerPayload.LANG_MARKER;
    /** 语言键与参数 / 参数之间的分隔符。 */
    public static final char ARG_SEP = CompletionBannerPayload.LANG_ARG_SEP;
    /**
     * 「这个参数是<b>语言键</b>」的标记：客户端把它解析成可翻译组件，而不是原样显示。
     * <p>用 {@link #argKey(String)} / {@link #itemNameArg(ItemStack)} / {@link #fluidNameArg(FluidStack)} 构造。</p>
     */
    public static final char ARG_KEY_MARKER = '\u0003';
    /**
     * 「这个参数是<b>配方类型注册 id</b>」的标记：客户端用 {@link RecipeTypeNames#of(String)}
     * 按玩家语言解析（服务端只知道自己那份 en_us 语言表，解析出来必然是英文）。
     */
    public static final char ARG_RECIPE_TYPE_MARKER = '\u0004';

    private RsccClientText() {
    }

    /**
     * 服务端调用：把「语言键 + 参数」编码成可过网络的字符串。
     * <p>参数只能是字符串可表达的东西（数字 / 玩家输入的纯文本 / {@link #argKey} 等嵌套键），
     * 不要传 {@code Component}，也不要把服务端已固化的名字 {@code getString()} 后当参数。
     */
    public static String key(final String translationKey, final Object... args) {
        if (translationKey == null || translationKey.isEmpty()) {
            return "";
        }
        final StringBuilder builder = new StringBuilder(translationKey.length() + 16).append(KEY_MARKER)
            .append(translationKey);
        if (args != null) {
            for (final Object arg : args) {
                builder.append(ARG_SEP).append(arg == null ? "" : arg);
            }
        }
        return builder.toString();
    }

    /** 服务端：把一个语言键声明成「参数」，客户端解析时按玩家语言翻译（嵌套本地化）。 */
    public static String argKey(final String translationKey) {
        return translationKey == null || translationKey.isEmpty()
            ? "" : ARG_KEY_MARKER + translationKey;
    }

    /** 服务端：把配方类型注册 id 声明成「参数」，客户端用 {@link RecipeTypeNames} 解析成显示名。 */
    public static String argRecipeType(final String recipeTypeId) {
        return recipeTypeId == null || recipeTypeId.isEmpty()
            ? "" : ARG_RECIPE_TYPE_MARKER + recipeTypeId;
    }

    /**
     * 服务端：物品名 → 玩家可见文本的参数（<b>不固化语言</b>）。
     *
     * <p>没有 {@code CUSTOM_NAME} 组件时送物品的 {@code descriptionId}（客户端按自己的语言翻译）；
     * 有自定义名时只能送那串字面量 —— 自定义名是<b>玩家输入的文本</b>，本来就没有可翻译的键，
     * 原样送达才是正确的（与修前行为一致，修前唯一的问题是无自定义名时也走了 {@code getString()}）。</p>
     */
    public static String itemNameArg(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        if (stack.has(DataComponents.CUSTOM_NAME)) {
            return stack.getHoverName().getString();
        }
        final String descriptionId = stack.getDescriptionId();
        return descriptionId == null || descriptionId.isEmpty()
            ? stack.getHoverName().getString() : argKey(descriptionId);
    }

    /** 服务端：流体名 → 玩家可见文本的参数（流体没有自定义名，一律走 {@code descriptionId}）。 */
    public static String fluidNameArg(final FluidStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        final String descriptionId = stack.getDescriptionId();
        return descriptionId == null || descriptionId.isEmpty()
            ? stack.getHoverName().getString() : argKey(descriptionId);
    }

    /**
     * 客户端调用：把一个（可能是嵌套键的）参数还原成「可翻译组件」或字面量。
     * <p>给所有「键 + 参数」解析点共用（{@link #resolve(String)} 与横幅 Toast 各一份），
     * 于是协议只有一处定义、不会漂移。
     */
    public static Object argument(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return switch (raw.charAt(0)) {
            case ARG_KEY_MARKER -> Component.translatable(raw.substring(1));
            case ARG_RECIPE_TYPE_MARKER -> Component.literal(RecipeTypeNames.of(raw.substring(1)));
            default -> raw;
        };
    }

    /** 该字符串是否是「服务端送来的语言键」（而不是已经本地化好的文本）。 */
    public static boolean isKeyText(final String raw) {
        return raw != null && !raw.isEmpty() && raw.charAt(0) == KEY_MARKER;
    }

    /**
     * 取出语言键本身（去掉标记与参数）；不是语言键文本时<b>原样返回</b>。
     *
     * <p>用途：服务端侧要把名字当<b>数据</b>落盘 / 比较（例如单元样板里绑定的自动合成仓名）时，
     * 存「语言键」比存某一时刻的本地化快照更稳（换语言不会让绑定失效）。</p>
     */
    public static String keyOf(final String raw) {
        if (!isKeyText(raw)) {
            return raw == null ? "" : raw;
        }
        final int sep = raw.indexOf(ARG_SEP, 1);
        return sep < 0 ? raw.substring(1) : raw.substring(1, sep);
    }

    /**
     * 客户端调用：把一个<b>段</b>解析成组件。三种形态统一在这里收口：
     * <ul>
     *     <li>{@code \u0001键...} ⇒ 按「语言键 + 参数」解析（{@link #resolve(String)}）；</li>
     *     <li>{@code \u0003键} / {@code \u0004配方 id} ⇒ 这一段本身就是个嵌套参数
     *     （横幅里「原料名单独占一段」的情形，见 {@code AssemblyWatchdog#materialRow}）；</li>
     *     <li>其它 ⇒ 字面量（玩家自定义名 / 旧存档 / 本来就是死字符串的旧数据）。</li>
     * </ul>
     */
    public static Component resolveSegment(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        if (isKeyText(raw)) {
            return resolve(raw);
        }
        if (raw.charAt(0) == ARG_KEY_MARKER || raw.charAt(0) == ARG_RECIPE_TYPE_MARKER) {
            return (Component) argument(raw);
        }
        return Component.literal(raw);
    }

    /**
     * 客户端调用：把 {@link #key} 编出来的字符串解析成 {@link Component}（玩家所在语言）。
     * <p>非语言键文本原样包装成字面量 —— 对调用方永远安全，不需要先判断。
     */
    public static Component resolve(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        if (!isKeyText(raw)) {
            return Component.literal(raw);
        }
        final String[] parts = raw.substring(1).split(String.valueOf(ARG_SEP), -1);
        if (parts.length <= 1) {
            return Component.translatable(parts[0]);
        }
        final Object[] args = new Object[parts.length - 1];
        // 参数也要能是「嵌套语言键 / 配方类型 id」：不能原样当字面量塞进译文，
        // 否则「铁锭」这类名字又会以服务端 en_us 的形态固化在玩家可见文本里（判据 F）。
        for (int i = 1; i < parts.length; i++) {
            args[i - 1] = argument(parts[i]);
        }
        return Component.translatable(parts[0], args);
    }

    /**
     * 客户端调用：同 {@link #resolve(String)}，但直接给玩家可见的字符串（给「只收 String」的
     * 第三方绘制/搜索入口用，例如 RS 自动合成仓管理器的分组名）。
     */
    public static String resolveText(final String raw) {
        if (!isKeyText(raw)) {
            return raw == null ? "" : raw;
        }
        return resolve(raw).getString();
    }
}
