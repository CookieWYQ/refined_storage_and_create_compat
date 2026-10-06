package cretae.cookiewyq.rs_create_compat.data;

import net.minecraft.ChatFormatting;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/**
 * 配方类型显示名解析工具（三处 GUI 共用，避免各写一份键查找逻辑）。
 * <p><b>解析顺序</b>：</p>
 * <ol>
 *     <li>{@code machine.rs_create_compat.<Path 首字母大写>}（兼容旧的机器类型键，如
 *     {@code create:pressing} → {@code machine.rs_create_compat.Pressing}）；</li>
 *     <li>{@code recipe.<namespace>.<path>}（其它模组常见的配方类型键）；</li>
 *     <li>{@code <namespace>.recipe.<path>}（Create 使用的键格式，如 {@code create.recipe.pressing}）。</li>
 * </ol>
 * <p>三种键都不存在时<b>不直接回退原始 id</b>，而是做「可读化」处理：丢掉命名空间、把下划线 /
 * 连字符换成空格、逐词首字母大写（{@code create:pressing} → {@code Pressing}，
 * {@code create:item_application} → {@code Item Application}，{@code minecraft:smelting} →
 * {@code Smelting}），避免界面上出现 {@code create:xxx} 这类生硬的技术串；入参为空串时返回空串。</p>
 * <p><b>专服安全</b>：使用通用类 {@link Language}（非客户端专属的 {@code I18n}），
 * 因此在服务端（如候选列表排序）调用不会抛 {@code NoClassDefFoundError}。</p>
 */
public final class RecipeTypeNames {
    /** 原始 id 行的前缀文案（如 {@code [id] create:pressing}）。 */
    private static final String LANG_ID_LABEL = "gui.rs_create_compat.recipe_type.id_label";
    /** id 为空 / 未设置时的占位灰字。 */
    private static final String LANG_ID_UNSET = "gui.rs_create_compat.recipe_type.id_unset";

    private RecipeTypeNames() {
    }

    /** 解析配方类型显示名（优先可用翻译，无翻译时可读化回退；空串返回空串）。 */
    public static String of(final String recipeType) {
        if (recipeType == null || recipeType.isEmpty()) {
            return "";
        }
        final int colon = recipeType.indexOf(':');
        final String namespace = colon < 0 ? "minecraft" : recipeType.substring(0, colon);
        final String path = colon < 0 ? recipeType : recipeType.substring(colon + 1);
        final Language language = Language.getInstance();
        for (final String key : new String[] {
            "machine.rs_create_compat." + capitalize(path),
            "recipe." + namespace + "." + path,
            namespace + ".recipe." + path}) {
            if (language.has(key)) {
                return language.getOrDefault(key, recipeType);
            }
        }
        return readable(path, recipeType);
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

    /** 首字母大写（用于 {@code machine.rs_create_compat.*} 这类旧键）。 */
    private static String capitalize(final String path) {
        if (path.isEmpty()) {
            return path;
        }
        return Character.toUpperCase(path.charAt(0)) + path.substring(1);
    }

    /**
     * 可读化回退：丢掉命名空间，把下划线 / 连字符当分隔符，逐词首字母大写后以空格连接。
     * <p>例：{@code pressing} → {@code Pressing}；{@code sandpaper_polishing} →
     * {@code Sandpaper Polishing}。分隔后没有任何词（path 全为分隔符）时回退完整原始 id。</p>
     */
    private static String readable(final String path, final String raw) {
        final StringBuilder builder = new StringBuilder();
        for (final String word : path.split("[_\\-]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(capitalize(word));
        }
        return builder.length() == 0 ? raw : builder.toString();
    }
}
