package cretae.cookiewyq.rs_create_compat.client.tooltip;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 「剪贴板 tooltip 附加显示」的开关 + 客户端指令（{@code /rs_create_compat clipboard-tooltip on|off}）。
 *
 * <h2>为什么是<b>客户端指令</b>，而不是像别的开关那样挂在服务端指令树上</h2>
 * <p>这项开关只影响<b>客户端怎么画 tooltip</b>：服务端既读不到也不需要它。做成客户端指令有三个直接好处：</p>
 * <ol>
 *     <li><b>改完立刻生效</b>：不需要 S2C 包、不需要重进世界（本类与
 *     {@link ClipboardTooltipHandler} 在同一个客户端 JVM 里）；</li>
 *     <li><b>联机也能用</b>：不用管理员权限，也不要求服务器装本模组 —— 与 Create 剪贴板内容本身
 *     （物品数据组件，已随物品同步到客户端）的可见范围一致；</li>
 *     <li><b>不与服务端指令冲突</b>：NeoForge 的客户端指令表是独立的一份
 *     （{@code ClientCommandHandler#runCommand} 先在客户端解析，解析不了才把原文发给服务端），
 *     因此 {@code /rs_create_compat clipboard-tooltip on} 只在客户端执行；
 *     而 {@code /rs_create_compat devlogs on} 这类服务端指令在客户端表里解析失败，
 *     照旧原样发给服务端，行为一个字都不变。</li>
 * </ol>
 *
 * <h2>为什么单独存一份客户端文件（而不是塞进 common 配置）</h2>
 * <p>工程里 {@code Config} 是 {@code ModConfig.Type.COMMON}（见 {@code RS_Create_Compat} 的
 * {@code modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC)}）：它在服务端与客户端
 * <b>各读各的本地文件</b>，没有「服务端改了客户端立刻知道」的同步。既然开关是纯客户端表现
 * （与 {@code Config#jadeBusTooltipMode} 的注释「本项是纯客户端表现：服务端读不到也用不到它」同一类），
 * 就把它落在客户端配置目录的 {@code rs_create_compat-client.properties} 里，与
 * {@code config/rs_create_compat-common.toml} 并排；由本类<b>只读一次、改时立刻落盘</b>，
 * 因此重启客户端后仍然保持玩家上次的选择。</p>
 *
 * <h2>默认值（第 82 轮改：默认<b>开</b>）</h2>
 * <p><b>为什么从「默认关」改成「默认开」</b>：用户实机反馈第 11 条
 * 「剪贴板的 tooltip 没有任何的区别」—— 查下来的第一原因就是本开关<b>默认关</b>：
 * 玩家不敲指令就永远看不到这轮做的附加显示，等于功能不存在。</p>
 * <p>这项开关是<b>纯客户端显示</b>（不写存档、不改服务端行为、不加网络包），
 * 所以「装上就该看见」比「默认与改动前逐字一致」更重要。仍然保留默认关的那类玩家的诉求：
 * 敲 {@code /rs_create_compat clipboard-tooltip off} 即可关掉，且会落盘保持。</p>
 * <p><b>兼容性</b>：已经存在 {@code config/rs_create_compat-client.properties} 且里面写了
 * {@code clipboardTooltip=false} 的玩家<b>保持关</b>（显式选择优先于新默认值）；
 * 文件不存在 / 文件里没有这个键 ⇒ 按新默认值<b>开</b>。</p>
 * <p><b>可发现性</b>：万一玩家自己关掉了，剪贴板 tooltip 仍会在「剪贴板里确实有本模组总线配置」时
 * 追加<b>一行</b>灰色提示（{@link ClipboardTooltipHandler} 的 {@code hint.off}），
 * 告诉他敲哪条指令可以看详情 —— 不再是「关掉之后连自己关掉了都看不出来」。</p>
 *
 * <h2>专用服务端安全</h2>
 * <p>本类只在 {@code Dist.CLIENT} 注册，且只被客户端事件引用；专用服务端不会加载它
 * （不新增任何服务端注册、不新增网络包）。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID, value = Dist.CLIENT)
public final class ClipboardTooltipSetting {
    /** 客户端设置文件名（放在 {@code config/} 下，与 common 配置并排）。 */
    private static final String FILE_NAME = "rs_create_compat-client.properties";
    /** 属性名（与指令名对应，便于玩家手改）。 */
    private static final String PROPERTY = "clipboardTooltip";

    private static final String KEY_SET = "message.rs_create_compat.clipboard_tooltip.set";
    private static final String KEY_CURRENT = "message.rs_create_compat.clipboard_tooltip.current";
    private static final String KEY_USAGE = "message.rs_create_compat.clipboard_tooltip.usage";
    private static final String KEY_ON = "message.rs_create_compat.clipboard_tooltip.on";
    private static final String KEY_OFF = "message.rs_create_compat.clipboard_tooltip.off";

    private static final Logger LOGGER = LoggerFactory.getLogger(ClipboardTooltipSetting.class);

    /** 当前开关值；<b>默认开</b>（第 82 轮改，理由见类注释）。 */
    private static volatile boolean enabled = true;
    /** 是否已经尝试读过磁盘（懒加载，避免在类初始化期碰 FML 路径）。 */
    private static volatile boolean loaded;

    private ClipboardTooltipSetting() {
    }

    /** 开关是否打开。tooltip 每帧都会问一次，因此命中时只有一次 volatile 读 + 一次布尔判断。 */
    public static boolean isEnabled() {
        ensureLoaded();
        return enabled;
    }

    /** 设置并立即落盘（幂等；写失败只记一条 WARN，不影响本次会话的开关值）。 */
    public static void setEnabled(final boolean value) {
        ensureLoaded();
        enabled = value;
        save();
    }

    /**
     * 懒加载：整个进程只读一次盘。读失败 ⇒ 保持默认<b>开</b>（绝不因为配置读不出来而崩客户端）。
     * <p>无论有没有文件，都会<b>记一条 INFO</b>说明「最终生效值 + 来源」—— 用户反馈「看不到区别」
     * 时，这一行就是唯一能自证「开关到底开没开」的证据（不必进游戏逐个试）。
     */
    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        final Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        if (!Files.isRegularFile(path)) {
            // 第一次使用：没有文件就是新的默认值「开」
            LOGGER.info("[rscc-clipboard-tooltip] 未找到 {}，按默认开启处理（敲 /rs_create_compat "
                + "clipboard-tooltip off 可关闭）", path);
            return;
        }
        final Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            enabled = Boolean.parseBoolean(properties.getProperty(PROPERTY, "true"));
            LOGGER.info("[rscc-clipboard-tooltip] 已读取 {}：{}={}（生效值：{}）",
                path, PROPERTY, properties.getProperty(PROPERTY, "true"), enabled ? "开" : "关");
        } catch (final IOException | RuntimeException exception) {
            LOGGER.warn("[rscc-clipboard-tooltip] 读取客户端设置失败，按默认开启处理：{}", path, exception);
        }
    }

    /** 落盘（只写一个键，便于玩家直接读 / 手改）。 */
    private static void save() {
        final Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        final Properties properties = new Properties();
        properties.setProperty(PROPERTY, Boolean.toString(enabled));
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                properties.store(writer, "rs_create_compat client settings");
            }
        } catch (final IOException | RuntimeException exception) {
            LOGGER.warn("[rscc-clipboard-tooltip] 保存客户端设置失败：{}", path, exception);
        }
    }

    /**
     * 注册客户端指令：{@code /rs_create_compat clipboard-tooltip on|off}。
     * <p>只输入到 {@code clipboard-tooltip} 一层时回报当前值与用法（与工程既有开关的「裸输入回报」一致）。
     * <p>根字面量 {@code rs_create_compat} 与服务的同名根在<b>各自的指令表</b>里独立存在
     * （客户端表只含客户端指令），不会互相覆盖；Brigadier 对同名 literal 本来也是合并子节点。</p>
     */
    @SubscribeEvent
    public static void onRegisterClientCommands(final RegisterClientCommandsEvent event) {
        final LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("rs_create_compat");
        root.then(Commands.literal("clipboard-tooltip")
            .then(Commands.literal("on")
                .executes(context -> set(context, true)))
            .then(Commands.literal("off")
                .executes(context -> set(context, false)))
            .executes(ClipboardTooltipSetting::report));
        event.getDispatcher().register(root);
    }

    /** 设置开关并回报结果。 */
    private static int set(final CommandContext<CommandSourceStack> context, final boolean enable) {
        setEnabled(enable);
        context.getSource().sendSuccess(
            () -> Component.translatable(KEY_SET, Component.translatable(enable ? KEY_ON : KEY_OFF)), false);
        return 1;
    }

    /** 裸输入：回报当前状态 + 用法提示。 */
    private static int report(final CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(
            () -> Component.translatable(KEY_CURRENT,
                Component.translatable(isEnabled() ? KEY_ON : KEY_OFF)), false);
        context.getSource().sendFailure(Component.translatable(KEY_USAGE));
        return 0;
    }
}
