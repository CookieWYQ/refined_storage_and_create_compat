import com.google.gson.Gson;
import cretae.cookiewyq.rs_create_compat.data.RecipeTypeNames;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 第 78 轮：配方类型显示名的<b>运行期</b>探针（不开游戏也能自证 Java 实现）。
 *
 * <p>为什么需要它：{@code tools/selfcheck_round78_recipe_type_l10n.py} 里的解析器是
 * <b>Python 复刻</b>；复刻一致不等于 Java 实现一致。本探针把本模组的 {@code lang/*.json}
 * 注入成一张「客户端语言表」（{@code Language.inject}），然后**真的调用**
 * {@link RecipeTypeNames#of(String)} / {@link RecipeTypeNames#display(String)}，逐条断言：</p>
 * <ol>
 *   <li>非空、且不是原始 id（不含 {@code :} / {@code _}）；</li>
 *   <li>同一 id 连查两次结果相同（走缓存）；</li>
 *   <li>{@code display()} 的文字 == {@code of()}；</li>
 *   <li>先 zh_cn 后 en_us 两遍（换语言 = 换 Language 实例）⇒ 缓存自动作废、名字确实变了。</li>
 * </ol>
 *
 * <p><b>怎么跑</b>（Windows，classpath 见 {@code build\mcjavac_args.txt} 的第一行）：</p>
 * <pre>
 * javac -proc:none -encoding UTF-8 -cp "build/lead_compile;%MC%;%VANILLA%;%FULL%" -d %TEMP% tools\probe_round78_recipe_type_names.java
 * java -cp "%TEMP%;build/lead_compile;%MC%;%VANILLA%;%FULL%" ProbeRound78 src\main\resources\assets\rs_create_compat\lang createbigcannons:big_cartridge_filling ...
 * </pre>
 * <p>其中 {@code %VANILLA%} 是原版 client jar（提供 {@code /assets/minecraft/lang/en_us.json}，
 * 否则 {@code Language.<clinit>} 会 NPE）。</p>
 */
public final class ProbeRound78 {
    private static final Gson GSON = new Gson();

    private ProbeRound78() {
    }

    private static Map<String, String> load(final String path) throws Exception {
        final String text = Files.readString(Path.of(path));
        final Map<String, String> map = GSON.fromJson(text, Map.class);
        final Map<String, String> out = new HashMap<>();
        for (final Map.Entry<String, String> entry : map.entrySet()) {
            out.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return out;
    }

    /** 冒充「客户端语言表」：en_us 打底，选中语言覆盖（与 ClientLanguage 的合并顺序一致）。 */
    private static final class TestLanguage extends Language {
        private final Map<String, String> data;

        TestLanguage(final Map<String, String> data) {
            this.data = data;
        }

        @Override
        public String getOrDefault(final String key, final String fallback) {
            return data.getOrDefault(key, fallback);
        }

        @Override
        public boolean has(final String key) {
            return data.containsKey(key);
        }

        @Override
        public boolean isDefaultRightToLeft() {
            return false;
        }

        @Override
        public FormattedCharSequence getVisualOrder(final FormattedText text) {
            return null;
        }
    }

    private static void install(final String langDir, final String locale) throws Exception {
        final Map<String, String> table = new HashMap<>(load(langDir + "/en_us.json"));
        if (!"en_us".equals(locale)) {
            table.putAll(load(langDir + "/" + locale + ".json"));
        }
        Language.inject(new TestLanguage(table));
    }

    public static void main(final String[] args) throws Exception {
        final String langDir = args[0];
        boolean bad = false;
        for (int pass = 0; pass < 2; pass++) {
            final String locale = pass == 0 ? "zh_cn" : "en_us";
            install(langDir, locale); // 新实例 ⇒ RecipeTypeNames 的缓存必须自动作废
            System.out.println("--- " + locale);
            for (int i = 1; i < args.length; i++) {
                final String id = args[i];
                final String first = RecipeTypeNames.of(id);
                final String second = RecipeTypeNames.of(id);
                final String display = RecipeTypeNames.display(id).getString();
                final boolean ok = !first.isBlank() && first.indexOf(':') < 0
                    && first.indexOf('_') < 0 && first.equals(second) && display.equals(first);
                if (!ok) {
                    bad = true;
                }
                System.out.printf("%s %-56s -> %s%s%n", ok ? "PASS" : "FAIL", id, first,
                    first.equals(second) ? "" : "  [second=" + second + "]");
            }
        }
        System.out.println(bad ? "PROBE_R78_FAIL" : "PROBE_R78_OK");
    }
}
