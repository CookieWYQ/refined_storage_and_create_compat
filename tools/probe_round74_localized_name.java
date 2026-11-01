import cretae.cookiewyq.rs_create_compat.support.RsccClientText;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 第 74 轮：RS「自动合成仓管理器」显示英文机器名的<b>离线复现</b>（不开游戏也能自证）。
 *
 * <p>复现依据（都可在工程里核对）：</p>
 * <ol>
 *   <li>RS 2.0.9 在<b>服务端</b>把名字压成 String：
 *       {@code AutocrafterManagerBlockEntity#getGroups() → getAutocrafterName().getString()}
 *       （javap 证实：invokeinterface Autocrafter.getAutocrafterName → Component.getString）；</li>
 *   <li>专服的语言表里有<b>模组的 en_us</b>：NeoForge
 *       {@code LanguageHook.loadModLanguages(MinecraftServer)}（ServerLifecycleHooks:103）
 *       把每个命名空间的 {@code lang/en_us.json} 并进 defaultLanguageTable
 *       ⇒ 模组键在专服上解析成<b>英文值</b>（不是裸键）；</li>
 *   <li>客户端选中的语言只注入客户端（{@code LanguageManager#onResourceManagerReload →
 *       Language.inject(clientLanguage)}）⇒ 服务端一旦压扁，客户端再也翻译不回来。</li>
 * </ol>
 *
 * <p>所以本探针做四件事：①用「模组 en_us 表」冒充专服语言表，证明修前 {@code getString()} 得到英文；
 * ②证明那串英文是死字符串（中文客户端无法翻译）；③证明修后「只送语言键」在中文/英文客户端分别解析成
 * 中文/英文；④打印 VERDICT 并以退出码表达结论。</p>
 *
 * 用法：javac -cp &lt;lead classpath&gt; ProbeRound74LocalizedName.java &amp;&amp;
 *      java -cp &lt;lead classpath&gt;;&lt;MC 资源 jar&gt;;. ProbeRound74LocalizedName [zh_cn.json] [en_us.json]
 */
final class ProbeRound74LocalizedName {
    private static final String KEY = "block.rs_create_compat.sequence_assembly_executor";

    public static void main(final String[] args) throws IOException {
        final StringBuilder report = new StringBuilder();
        final Map<String, String> zh = loadLang(Path.of(args.length > 0 ? args[0]
            : "src/main/resources/assets/rs_create_compat/lang/zh_cn.json"));
        final Map<String, String> en = loadLang(Path.of(args.length > 1 ? args[1]
            : "src/main/resources/assets/rs_create_compat/lang/en_us.json"));
        final String enValue = en.get(KEY);
        final String zhValue = zh.get(KEY);
        final java.util.function.Consumer<String> out = line -> {
            System.out.println(line);
            report.append(line).append('\n');
        };

        // ===== 1) 专服语言表（NeoForge 会把模组 en_us 并进来）⇒ 修前的名字被固化成英文 =====
        final String preFixName = withLanguage(en, () -> Component.translatable(KEY).getString());
        out.accept("[1] 专服 Language（含模组 en_us）解析 translatable : " + preFixName);
        out.accept("    与 en_us 值相同？                                : "
            + preFixName.equals(enValue) + "（修前 getGroups() 的分组键就是它）");

        // ===== 2) 客户端拿到的是死字符串：中文语言表也翻不回来 =====
        final String clientOfDeadString = withLanguage(zh,
            () -> Component.literal(preFixName).getString());
        out.accept("[2] 中文客户端拿到那串英文后                        : " + clientOfDeadString
            + "  （能翻回中文？ " + clientOfDeadString.equals(zhValue) + "）");

        // ===== 3) 修后：服务端只发语言键，客户端各自解析 =====
        final String wire = RsccClientText.key(KEY);
        out.accept("[3] 修后过网络的字符串                              : " + escape(wire));
        final String zhText = withLanguage(zh, () -> RsccClientText.resolveText(wire));
        final String enText = withLanguage(en, () -> RsccClientText.resolveText(wire));
        out.accept("[4] 中文客户端解析                                  : " + zhText);
        out.accept("[5] 英文客户端解析                                  : " + enText);

        // ===== 4) 结论 =====
        final boolean serverIsEnglish = preFixName.equals(enValue);
        final boolean deadStaysEnglish = !clientOfDeadString.equals(zhValue);
        final boolean zhOk = zhText.equals(zhValue);
        final boolean enOk = enText.equals(enValue);
        final boolean ok = serverIsEnglish && deadStaysEnglish && zhOk && enOk;
        out.accept("");
        out.accept("VERDICT=" + (ok ? "PASS" : "FAIL")
            + "  server_en=" + serverIsEnglish
            + "  prefix_dead_string_stays_en=" + deadStaysEnglish
            + "  zh_client=" + zhText + "  en_client=" + enText);
        Files.writeString(Path.of("_audit_tmp/probe74_report.txt"), report.toString(),
            StandardCharsets.UTF_8);
        System.out.println("WROTE _audit_tmp/probe74_report.txt");
        if (!ok) {
            System.exit(1);
        }
    }

    private static String withLanguage(final Map<String, String> map,
                                       final java.util.function.Supplier<String> body) {
        final Language previous = Language.getInstance();
        Language.inject(new Language() {
            @Override
            public String getOrDefault(final String key, final String fallback) {
                return map.getOrDefault(key, fallback);
            }

            @Override
            public boolean has(final String key) {
                return map.containsKey(key);
            }

            @Override
            public boolean isDefaultRightToLeft() {
                return false;
            }

            @Override
            public FormattedCharSequence getVisualOrder(
                final net.minecraft.network.chat.FormattedText text) {
                return sink -> true;
            }
        });
        try {
            return body.get();
        } finally {
            Language.inject(previous);
        }
    }

    /** 极简 JSON 扁平表读取（只认 "键": "值"，语言文件是扁平的）。 */
    private static Map<String, String> loadLang(final Path path) throws IOException {
        final String text = Files.readString(path, StandardCharsets.UTF_8);
        final Map<String, String> map = new LinkedHashMap<>();
        int index = 0;
        while ((index = text.indexOf('"', index)) >= 0) {
            final int keyEnd = text.indexOf('"', index + 1);
            if (keyEnd < 0) {
                break;
            }
            final String key = text.substring(index + 1, keyEnd);
            int cursor = keyEnd + 1;
            while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) {
                cursor++;
            }
            if (cursor >= text.length() || text.charAt(cursor) != ':') {
                index = keyEnd + 1;
                continue;
            }
            cursor++;
            while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) {
                cursor++;
            }
            if (cursor >= text.length() || text.charAt(cursor) != '"') {
                index = keyEnd + 1;
                continue;
            }
            final StringBuilder value = new StringBuilder();
            cursor++;
            while (cursor < text.length()) {
                final char ch = text.charAt(cursor);
                if (ch == '\\' && cursor + 1 < text.length()) {
                    value.append(text.charAt(cursor + 1));
                    cursor += 2;
                    continue;
                }
                if (ch == '"') {
                    break;
                }
                value.append(ch);
                cursor++;
            }
            map.put(key, value.toString());
            index = cursor + 1;
        }
        return map;
    }

    private static String escape(final String raw) {
        final StringBuilder builder = new StringBuilder();
        for (final char ch : raw.toCharArray()) {
            if (ch < 0x20) {
                builder.append(String.format("\\u%04x", (int) ch));
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private ProbeRound74LocalizedName() {
    }
}
