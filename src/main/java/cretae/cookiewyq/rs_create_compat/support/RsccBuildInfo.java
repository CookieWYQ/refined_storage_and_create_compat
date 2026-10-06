package cretae.cookiewyq.rs_create_compat.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * <b>「这段日志到底是哪一版代码跑出来的」的唯一答案来源</b>（启动时打一行，之后不再输出）。
 *
 * <h2>为什么必须有它（§7.3 审计结论 ⑥）</h2>
 * <p>上一轮取证时踩到的坑是：{@code run/logs/latest.log} 里<b>没有 git hash、也没有编译时间</b>，
 * 只有 {@code 0.0.1-SNAPSHOT}。于是「日志里的行为」与「磁盘上的源码」之间<b>只能靠文件 mtime 猜</b>：
 * 实测同一份日志里 {@code RsccFlowLedger.class} 比它的源码旧、{@code RsccAssemblyDebug.class}
 * 停留在 2026-10-01，而 {@code AssemblyWatchdog.java} / 最新编译都<b>晚于</b>那次游戏会话
 * ⇒ 按项目自己的判据（{@code docs/SEQUENCE_ASSEMBLY_LOG_GUIDE.md}「会话起点必须晚于本次构建」）
 * 那份日志<b>连基线都算不上</b>，却没有人能从日志本身看出这一点。</p>
 *
 * <p>本类把这件事变成<b>可自证</b>的：启动时打一行 {@value #PREFIX}，字段固定顺序，
 * 其中 {@code revision} / {@code built} / {@code builtAt} 来自随构建生成的
 * {@value #RESOURCE}（由 {@code tools/gen_build_info.py} 写入，Gradle 的
 * {@code generateBuildInfo} 任务与 {@code tools/manual_compile.ps1} 都会刷新它）。</p>
 *
 * <p>典型输出：</p>
 * <pre>
 *   [rscc-build] mod=rs_create_compat version=0.0.1-SNAPSHOT revision=e347372+ (branch=main, dirty)
 *                built=2026-10-04T18:52:07+08:00 sources=323 mc=1.21.1 neoforge=21.1.248 java=21.0.4 os=Windows 11
 * </pre>
 *
 * <h2>怎么用这一行判定「日志能不能作证」</h2>
 * <ol>
 *     <li>取日志里<b>这一次游戏会话</b>的 {@value #PREFIX} 行（最新一次启动那一条）；</li>
 *     <li>比较 {@code revision} / {@code built} 与当前工作区 {@code git rev-parse HEAD} / 最后一次编译时间；</li>
 *     <li>两者不一致 ⇒ 这份日志只能当<b>基线</b>，不能用来证明「修复生效」（正是上一轮的教训）。</li>
 * </ol>
 * <p>配套的无 GUI 判定：{@code python tools/verify_build_stamp.py} —— 它把上面三步做成断言
 * （缺 {@value #PREFIX} 行、revision 不符、编译晚于会话起点，都会 <b>FAIL</b> 而不是静默通过）。</p>
 *
 * <h2>设计约束</h2>
 * <ul>
 *     <li><b>绝不抛</b>：任何字段取不到就写 {@code ?}；读资源 / 反射全部包在 try-catch 里
 *     —— 一行日志绝不能影响游戏启动。</li>
 *     <li><b>只打一次</b>：{@link #logOnce()} 幂等（多入口重复调用只输出一行）。</li>
 *     <li><b>不碰网络 / 不碰文件系统之外的东西</b>：只读自己 jar 内的一个 properties 资源。</li>
 * </ul>
 */
public final class RsccBuildInfo {
    /** 启动指纹行的统一前缀（便于 grep：{@code [rscc-build]}）。 */
    public static final String PREFIX = "[rscc-build]";
    /** jar 内的构建指纹资源（由 {@code tools/gen_build_info.py} 生成）。 */
    public static final String RESOURCE = "/build_info.properties";

    private static final Logger LOGGER = LoggerFactory.getLogger(RsccBuildInfo.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** {@link #logOnce()} 的幂等标记（服务器反复启停的集成环境里也只打一次）。 */
    private static volatile boolean logged;

    /** 从 {@value #RESOURCE} 读到的指纹（读不到时全为 {@code ?}，绝不抛）。 */
    private static final String REVISION;
    private static final String BRANCH;
    private static final String DIRTY;
    private static final String BUILT_AT;
    private static final String SOURCES;
    private static final String SOURCE_EPOCH;
    /** 资源读取是否成功（供自检脚本判定「这个 jar 带不带指纹」）。 */
    private static final boolean STAMPED;

    static {
        final Properties properties = new Properties();
        boolean loaded = false;
        try (InputStream stream = RsccBuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (stream != null) {
                properties.load(stream);
                loaded = true;
            }
        } catch (final Exception ignored) {
            // 资源缺失 / 损坏：一律降级为 "?"，绝不影响启动
        }
        STAMPED = loaded;
        REVISION = value(properties, "revision");
        BRANCH = value(properties, "branch");
        DIRTY = value(properties, "dirty");
        BUILT_AT = value(properties, "builtAt");
        SOURCES = value(properties, "sources");
        SOURCE_EPOCH = value(properties, "sourceEpochSeconds");
    }

    private RsccBuildInfo() {
    }

    private static String value(final Properties properties, final String key) {
        final String raw = properties.getProperty(key);
        return raw == null || raw.isBlank() ? "?" : raw.trim();
    }

    // ==================== 对外只读访问器（供自检 / 诊断导出使用） ====================

    /** 构建指纹是否随包提供（{@code false} = 这个 jar 是旧构建 / 资源被裁掉）。 */
    public static boolean isStamped() {
        return STAMPED;
    }

    /** 编译时的 git 短 hash（含「工作区是否脏」后缀，如 {@code e347372+dirty}）；取不到为 {@code ?}。 */
    public static String revision() {
        return REVISION;
    }

    /** 编译时的分支名；取不到为 {@code ?}。 */
    public static String branch() {
        return BRANCH;
    }

    /** 编译时工作区是否脏（{@code true} / {@code false} / {@code ?}）。 */
    public static String dirty() {
        return DIRTY;
    }

    /** 编译时间（ISO-8601 带时区）；取不到为 {@code ?}。 */
    public static String builtAt() {
        return BUILT_AT;
    }

    /** 编译时的源码文件数（{@code ?} = 未知）。 */
    public static String sources() {
        return SOURCES;
    }

    /** 编译时间戳（毫秒，供「会话起点是否晚于构建」的机器判定）；未知返回 {@code -1}。 */
    public static long builtAtMillis() {
        try {
            return Long.parseLong(SOURCE_EPOCH) * 1000L;
        } catch (final NumberFormatException ignored) {
            return -1L;
        }
    }

    /** 当前时间（同一格式化 + 时区，供日志与会话起点对照）。 */
    public static String now() {
        return LocalDateTime.now(ZoneId.systemDefault()).format(ISO);
    }

    // ==================== 输出 ====================

    /**
     * 打一行<b>构建指纹</b>（幂等）。调用点：服务器启动（{@code RsccDiag#onServerStarted}）。
     *
     * <p>字段固定顺序：{@code mod / version / revision / branch / dirty / built / builtAt / sources /
     * mc / neoforge / java / os}。其中 {@code built=} 是<b>即时生成</b>的「本次启动时刻」之外的编译时间，
     * 两者放在一行里对照，避免再出现「拿运行时刻当编译时间」的误读。</p>
     */
    public static void logOnce() {
        if (logged) {
            return;
        }
        logged = true;
        LOGGER.info("{} mod=rs_create_compat version={} revision={}{} branch={} built={} sources={} "
                + "mc={} neoforge={} java={} os={}",
            PREFIX, versionOf(), REVISION,
            "true".equalsIgnoreCase(DIRTY) ? "+dirty" : "",
            BRANCH, BUILT_AT, SOURCES,
            mcVersion(), neoVersion(), javaVersion(), osName());
        if (!STAMPED) {
            // 明确告警而不是静默：没有指纹时，「日志 ↔ 源码」的对应关系又回到了靠 mtime 猜的状态。
            LOGGER.warn("{} build stamp missing ({} not found in this jar) -> "
                    + "log-to-source correspondence is NOT provable for this session; "
                    + "run tools/gen_build_info.py (or a full build) and restart",
                PREFIX, RESOURCE);
        }
    }

    /** 本模组版本：优先问加载器（{@code ModList.get().getModContainerById(...).getModInfo().getVersion()}），
     *  失败退回 {@value #RESOURCE} 里的 {@code modVersion}。 */
    private static String versionOf() {
        final String viaLoader = modVersionViaLoader();
        if (viaLoader != null) {
            return viaLoader;
        }
        try (InputStream stream = RsccBuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return "?";
            }
            final Properties properties = new Properties();
            properties.load(stream);
            return value(properties, "modVersion");
        } catch (final Exception ignored) {
            return "?";
        }
    }

    /**
     * 用反射读模组版本（{@code ModList.get().getModContainerById(MODID) → getModInfo().getVersion()}）。
     * <p>刻意走反射：本类属于「必须在任何加载阶段都能安全调用」的日志设施，绝不因为某个加载器
     * 版本的方法签名差异而编译失败（编译期硬编码在 {@code manual_compile.ps1} 的本地 jar 集合下也会更脆）。
     * 任何一步取不到都返回 {@code null}，由调用方降级。</p>
     */
    private static String modVersionViaLoader() {
        try {
            final Class<?> modListType = Class.forName("net.neoforged.fml.ModList");
            final Object modList = modListType.getMethod("get").invoke(null);
            if (modList == null) {
                return null;
            }
            final Object container = modListType
                .getMethod("getModContainerById", String.class)
                .invoke(modList, "rs_create_compat");
            if (container == null) {
                return null;
            }
            final Object modInfo = container.getClass().getMethod("getModInfo").invoke(container);
            if (modInfo == null) {
                return null;
            }
            final Object version = modInfo.getClass().getMethod("getVersion").invoke(modInfo);
            return version == null ? null : version.toString();
        } catch (final Throwable ignored) {
            return null;
        }
    }

    /** Minecraft 版本（走共享常量，避免多写一份口径）。 */
    private static String mcVersion() {
        try {
            return net.minecraft.SharedConstants.getCurrentVersion().getName();
        } catch (final Throwable ignored) {
            return "?";
        }
    }

    /**
     * NeoForge 版本（反射 {@code FMLLoader.versionInfo().neoForgeVersion()}）。
     * <p>与 {@link #modVersionViaLoader()} 同一理由走反射：日志设施不能在加载器方法签名变化时编译失败；
     * 取不到就写 {@code ?}，绝不影响启动。</p>
     */
    private static String neoVersion() {
        try {
            final Class<?> loader = Class.forName("net.neoforged.fml.loading.FMLLoader");
            final Object versionInfo = loader.getMethod("versionInfo").invoke(null);
            if (versionInfo == null) {
                return "?";
            }
            final Object version = versionInfo.getClass().getMethod("neoForgeVersion").invoke(versionInfo);
            return version == null ? "?" : version.toString();
        } catch (final Throwable ignored) {
            return "?";
        }
    }

    /** Java 运行时版本（诊断用：确认确实跑在 Java 21 上）。 */
    private static String javaVersion() {
        try {
            return System.getProperty("java.version", "?");
        } catch (final Throwable ignored) {
            return "?";
        }
    }

    private static String osName() {
        try {
            return System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "?");
        } catch (final Throwable ignored) {
            return "?";
        }
    }
}
