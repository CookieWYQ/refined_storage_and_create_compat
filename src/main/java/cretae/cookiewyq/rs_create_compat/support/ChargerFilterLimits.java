package cretae.cookiewyq.rs_create_compat.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 充电过滤的<b>硬上限</b>与<b>玩家输入解析</b>的唯一出处（用户第 99 轮反馈：「第三个改了他」）。
 *
 * <h2>为什么这份「上限」必须是一个独立类、而且必须是纯 JDK</h2>
 * <p>第 98 轮的黑/白名单功能被复核时指出两个漏洞，本类就是它们的修法：</p>
 * <ol>
 *     <li><b>C2S 包没有条数上限</b>：{@code SetChargerFilterPacket} 的名单条数由客户端给。
 *     第 98 轮只做到「不按对端给的数量预分配内存」，但一包 20 万条 {@code ResourceLocation}
 *     （每条几十字节）仍然能一路进到 {@code ChargingFilter} 里，并随 {@code setChanged()}
 *     落盘成一份巨大的 NBT。上限<b>不能散在包里各写一遍</b>：包里写了 1024、存档加载却没写，
 *     只要有人手改 NBT 就又能塞满内存；反之包里没写、只有存档写了，恶意包仍能先撑爆一次解码器。
 *     因此数字只在这里定义一次，网络层（编/解码）、存档层（{@code load}）、清洗层
 *     （{@link ChargingFilter#of}）、界面层（勾选/添加）全部<b>引用同一组常量</b>。</li>
 *     <li><b>32 位无横线 UUID 被当成玩家名</b>：界面的「添加」原先只认 36 字符带横线的写法，
 *     玩家粘贴 {@code 069a79f444e94726a5befca90e38aaf5} 这种（很多工具输出的）裸十六进制时，
 *     它会被当成一个<b>永远不可能命中</b>的玩家名存进名单 —— 玩家会以为「我加了但没用」。
 *     {@link #parsePlayerId} 同时接受两种写法，并且只在<b>整串都是十六进制</b>时才认 UUID，
 *     这样它永远不会把真正的玩家名（最长 16 字符）误判成 UUID。</li>
 * </ol>
 *
 * <h2>为什么本类不得引用任何 Minecraft 类型</h2>
 * <p>「上限」与「UUID 写法」都是纯字符串 / 集合问题，没有一条需要 {@code Level}、注册表或客户端。
 * 保持纯 JDK 带来两个实际好处：①专用服务端加载它时不可能链接到客户端类；
 * ②自检可以用 {@code javac}/{@code java} 直接编译执行本类（无需 MC classpath），
 * 于是「解析规则」「截断规则」是<b>真跑过</b>的，而不是只对着源码 grep。</p>
 *
 * <h2>上限为什么是这个数量级</h2>
 * <p>候选池本身的上限是 {@code ChargerFilterCandidates.MAX_SEARCH_RESULTS = 512}（一次搜索最多列
 * 512 条），所以 1024 条物品名单已经等于「连续两次满页搜索全勾」；256 条玩家名单远超任何一个
 * 服务器的实际玩家数。这些数字同时把最坏一包的大小压在几十 KB（远低于网络帧上限），
 * 而「恶意包」无论声称多少条，解码器最多只保留上限那么多条 —— 内存上界因此与包里写了什么无关。</p>
 *
 * <h2>截断而不是拒收</h2>
 * <p>超限时一律<b>保留前 N 条、丢弃其余</b>（而不是把整份配置判非法）：过滤配置的语义是
 * 「名单里有什么」，少几条最多是「少拦了几种物品」，而「整份判非法」会让玩家刚勾的一切
 * 全部消失 —— 后者才是灾难。唯一需要调用方配合的是界面：它必须在加不进去时<b>明确提示</b>
 * 「名单已满」，不能点了没反应（本工程硬规则）。</p>
 */
public final class ChargerFilterLimits {
    /** 掉落物名单 / 玩家身上物品名单各自的硬上限（条）。 */
    public static final int MAX_ITEM_ENTRIES = 1024;
    /**
     * 「世界中的方块」名单的硬上限（条），以及范围扫描候选池（{@code seenBlocks}）的上限。
     *
     * <p><b>为什么与物品名单同量级</b>：方块名单同样是「一页最多 512 条」的候选池里连续勾两次
     * 就能填满的量级；同时它也充当方块实体每 tick 收集「本机范围内有 FE 能力的方块 id」时
     * 的<b>容量上界</b>（大范围 + 密集机器区里方块实体数量可以上千，收集集合必须有界，
     * 否则一个 100×100×100 的范围升级能把内存吃掉）。</p>
     *
     * <p><b>为什么两个用途共用一个数字</b>：候选池与名单是同一批 id 的两个集合，若候选池上限
     * 比名单上限小，「范围内的方块」就永远勾不完；若候选池更大，多出来的部分界面也存不下。
     * 共用常量保证「候选池 ≥ 名单」这条不变式天然成立。</p>
     */
    public static final int MAX_BLOCK_ENTRIES = 1024;
    /** 玩家名名单的硬上限（条）。 */
    public static final int MAX_PLAYER_ENTRIES = 256;
    /** 玩家 UUID 名单的硬上限（条）。 */
    public static final int MAX_PLAYER_ID_ENTRIES = 256;
    /** 单条玩家名的最大长度（原版名 16，留一倍余量；超长的一律丢弃）。 */
    public static final int MAX_NAME_LENGTH = 32;
    /**
     * 「本存档进过服的玩家」候选池的硬上限（条）。
     * <p>这份数据随存档无限增长（每来一个新玩家 +1），所以下发时也要有界：
     * 1024 条足以覆盖任何真实服务器，同时保证快照包大小恒定。</p>
     */
    public static final int MAX_KNOWN_PLAYERS = 1024;
    /** 带横线的标准 UUID 文本长度。 */
    public static final int DASHED_UUID_LENGTH = 36;
    /** 裸十六进制 UUID 文本长度（无横线）。 */
    public static final int BARE_HEX_UUID_LENGTH = 32;

    private ChargerFilterLimits() {
    }

    /**
     * 往集合里放一条，且不超过 {@code max}。
     *
     * @return {@code true} = 已放入 / 本来就是重复的（不算超限）；{@code false} = <b>已满</b>，
     * 调用方应当停下并（界面侧）给出提示。{@code value == null} 按「忽略」处理、返回 {@code true}，
     * 免得一个 null 让调用方误报「名单已满」。
     */
    public static <T> boolean addBounded(final Set<T> target, final T value, final int max) {
        if (target == null) {
            return true;
        }
        if (value == null || target.contains(value)) {
            return true;
        }
        if (target.size() >= max) {
            return false;
        }
        target.add(value);
        return true;
    }

    /**
     * 界面的「勾选 / 取消勾选」语义：<b>已勾选 ⇒ 取消；否则尝试加入</b>。
     *
     * <p>为什么不把这两步散在界面里：它看起来只有两行，但「先 remove 判断、再 add」的写法
     * 极容易写成「无条件 add」—— 那样勾选框点一次就永远关不掉（第 99 轮写这段时真的写错过一次）。
     * 语义一旦在这里，就可以被纯 JDK 自检直接执行验证：{@code 勾上 → 再点 → 必须为空}。</p>
     *
     * @return {@code true} = 状态已改变且合法；{@code false} = <b>名单已满、什么都没改</b>
     * （调用方必须给玩家提示，不能点了没反应）。
     */
    public static <T> boolean toggleBounded(final Set<T> target, final T value, final int max) {
        if (target == null || value == null) {
            return true;
        }
        if (target.remove(value)) {
            return true; // 本来就是勾上的 ⇒ 这次是取消
        }
        if (target.size() >= max) {
            return false;
        }
        target.add(value);
        return true;
    }

    /**
     * 取前 {@code max} 条（<b>副本</b>，含 null 的条目被跳过）。
     * <p>预分配容量取 {@code min(source.size(), max)}：{@code max} 是常量，
     * 所以即便 {@code source.size()} 来自对端也无法让这里分配出超界限的内存。</p>
     */
    public static <T> List<T> clamp(final Collection<T> source, final int max) {
        if (source == null || max <= 0) {
            return new ArrayList<>();
        }
        final List<T> out = new ArrayList<>(Math.min(source.size(), max));
        for (final T value : source) {
            if (value == null) {
                continue;
            }
            if (out.size() >= max) {
                break;
            }
            out.add(value);
        }
        return out;
    }

    /**
     * 把一条原始输入洗成「名单里的玩家名」。
     *
     * @return 可入库的小写名字（已 trim）；{@code null} = 空、或长度超过
     * {@link #MAX_NAME_LENGTH}，调用方应当丢弃（不抛异常、不静默塞进名单）。
     */
    public static String normalizeName(final String rawName) {
        if (rawName == null) {
            return null;
        }
        final String name = rawName.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            return null;
        }
        return name;
    }

    /**
     * 解析玩家输入里的 UUID：同时接受<b>36 字符带横线</b>与<b>32 字符裸十六进制</b>。
     *
     * <p>为什么不是「能 {@code UUID.fromString} 就认」：{@code UUID.fromString} 对
     * {@code 1-1-1-1-1} 这类不标准写法在不同 JDK 上宽严不一，而本方法的结果会直接决定
     * 「这条输入进 UUID 名单还是进名字名单」—— 语义不能依赖 JDK 版本。因此先按长度与
     * 十六进制字符集<b>自己判</b>，再交给 {@code UUID.fromString} 做最后一步。</p>
     *
     * @return 解析结果；{@code null} = 看起来不是 UUID（调用方应回落到「按玩家名处理」）。
     */
    public static UUID parsePlayerId(final String rawText) {
        if (rawText == null) {
            return null;
        }
        final String text = rawText.trim();
        if (text.length() == DASHED_UUID_LENGTH) {
            return parseStrict(text, false);
        }
        if (text.length() == BARE_HEX_UUID_LENGTH && isHex(text)) {
            return parseStrict(withDashes(text), true);
        }
        return null;
    }

    /** 32 位裸十六进制 → 带横线的标准写法（不校验内容，校验在 {@link #parsePlayerId}）。 */
    public static String withDashes(final String bareHex) {
        if (bareHex == null || bareHex.length() != BARE_HEX_UUID_LENGTH) {
            return bareHex;
        }
        return bareHex.substring(0, 8) + "-" + bareHex.substring(8, 12) + "-"
            + bareHex.substring(12, 16) + "-" + bareHex.substring(16, 20) + "-"
            + bareHex.substring(20);
    }

    /** 整串是否全为十六进制字符（0-9 / a-f / A-F）。 */
    public static boolean isHex(final String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            final boolean digit = c >= '0' && c <= '9';
            final boolean lower = c >= 'a' && c <= 'f';
            final boolean upper = c >= 'A' && c <= 'F';
            if (!digit && !lower && !upper) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析并校验形状。
     *
     * @param bare 传入的 {@code text} 是否是「由裸十六进制补横线得来」的（那种串里的横线是自己加的，
     *             只需校验十六进制字符；手输的 36 字符写法要逐位校验横线位置，否则
     *             {@code 12345678x1234-...} 这类会被 JDK 的宽松解析放过）
     */
    private static UUID parseStrict(final String text, final boolean bare) {
        if (text.length() != DASHED_UUID_LENGTH) {
            return null;
        }
        if (!bare) {
            // 逐位校验手输串：横线必须正好在 8/13/18/23，其余位必须是十六进制
            final StringBuilder hex = new StringBuilder(BARE_HEX_UUID_LENGTH);
            for (int i = 0; i < text.length(); i++) {
                final char c = text.charAt(i);
                if (i == 8 || i == 13 || i == 18 || i == 23) {
                    if (c != '-') {
                        return null;
                    }
                } else {
                    hex.append(c);
                }
            }
            if (!isHex(hex.toString())) {
                return null;
            }
        }
        try {
            return UUID.fromString(text);
        } catch (final IllegalArgumentException ignored) {
            return null;
        }
    }
}
