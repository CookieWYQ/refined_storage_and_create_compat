package cretae.cookiewyq.rs_create_compat.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 归流缓存仓「阻塞名单」的<b>硬上限</b>与<b>有界加入</b>算法的唯一出处（第 117 轮）。
 *
 * <h2>为什么需要这个类</h2>
 * 用户第 ③ 条反馈之后，「阻塞」从「一个具体资源 id」扩展成<b>两种名单</b>：具体 id 名单
 * （物品 / 流体各一份）与按标签阻塞名单（物品 / 流体各一份）。但这四个集合的<b>增长完全由客户端驱动</b>：
 * {@code SetCollectionBlockedPacket} 每条带一个 id 与任意条数的标签，服务端
 * {@code CollectionCacheBlockEntity#setBlocked / #setBlockedTags} 直接 {@code Set#add}。于是有两条口子：
 * <ol>
 *     <li><b>条数无上限</b>：反复发「每包换一个新 id / 一个新标签」的请求，就能把四个集合无限撑大 ——
 *     它们会进 {@code writeBlocked} 落盘（存档膨胀），并随每次打开界面的
 *     {@code SyncCollectionMarkersPacket} 全量回发（同步膨胀）。这与第 98 轮充电过滤、第 107 轮
 *     游戏规则、第 114～116 轮标记表 / 导入包是同一类缺陷。</li>
 *     <li><b>标签包无解码上限</b>：原来的 {@code ByteBufCodecs.list()} 条数由对端给，
 *     改包一次能塞进任意多条 {@code ResourceLocation}（每条还能长达协议允许的字符串上界）。</li>
 * </ol>
 * 修法与前面几轮同一套纪律：<b>数字只在这里定义一次</b>，网络层（解码）、方块实体（增长）、
 * 提示文案（上限值）全部引用本类常量；不允许在别处再写一遍字面量。
 *
 * <h2>为什么是「只约束增长」，而不是「读档截断」</h2>
 * 旧存档（或本轮之前被改包撑大的存档）里的名单可能已经超过上限。读档时截断会<b>直接吞掉玩家的阻塞配置</b>，
 * 而阻塞名单是「我已经明确说不要这些资源回流」的意图 —— 丢掉它等于让被阻塞的资源重新开始回流，
 * 属于静默改变语义。第 116 轮单元样板库复核已经踩过同一条（{@code rscc$resize} 只在「从 ≤ 上限跨过」
 * 时拒绝，于是超限旧库还能继续长）。因此本轮的规则与之逐字相同：
 * <b>读档原样读回（{@code loadBlocked} 不判上限）；所有新增在超限时整体拒绝；删除永远允许</b>。
 *
 * <h2>为什么本类不得引用任何 Minecraft 类型</h2>
 * 与 {@link ChargerFilterLimits} 同样的理由：上限与「有界加入」都是纯集合问题，没有一条需要
 * {@code Level} / 注册表 / 客户端。保持纯 JDK 带来三个实际好处：
 * ①专用服务端加载本类时不可能链接到客户端类；②自检可以用 {@code javac} / {@code java}
 * 直接编译执行 {@link #fits} 与 {@link #clamp}（「真跑过」而不是只对着源码 grep）；
 * ③通用算法（{@link #clamp}）只有一份实现 —— 直接委托 {@link ChargerFilterLimits#clamp}，
 * 不在这里再抄一遍循环。
 *
 * <h2>为什么上限是这个量级</h2>
 * <p><b>具体 id 名单 1024 条</b>：阻塞的典型用法是「这个缓存仓不收某几类东西」，玩家是逐个
 * Ctrl+左键点出来的；界面上一次能列出的缓存格数量远小于 1024。1024 条注册名落盘约 20～50 KB，
 * 同步包同样量级，对正常存档无感。</p>
 * <p><b>按标签名单 256 条</b>：一条标签就挡住<b>一整族</b>资源（如「所有板子」），
 * 256 条标签已能覆盖整个大型整合包的标签体系，正常玩家通常个位数。</p>
 * <p><b>单包 128 条标签</b>：客户端下发的标签集合是「示例物自身带的标签里被勾选的那几条」，
 * 真实物品的标签数通常 &lt; 30，128 留了 4 倍余量；解码上限只是挡住伪造的计数。</p>
 */
public final class CollectionBlockedLimits {
    /** 具体资源 id 名单的上限（物品、流体各一份独立计数）。 */
    public static final int MAX_BLOCKED_IDS = 1024;
    /** 按标签阻塞名单的上限（物品、流体各一份独立计数）。 */
    public static final int MAX_BLOCKED_TAGS = 256;
    /**
     * 单个 C2S 包最多被<b>暂存</b>的标签条数（解码层闸门；对端声明的条数仍会被全部消费以保持流对齐）。
     */
    public static final int MAX_TAGS_PER_PACKET = 128;
    /**
     * 单条标签注册名的最大长度（字符）。
     * <p>真实标签 id（如 {@code minecraft:planks} / {@code c:ingots}）通常 &lt; 40 字符，
     * 整合包里的长命名空间也很少超过 80。超过这个长度的「标签」既不可能存在于任何注册表，
     * 又会按原样落盘，因此在<b>写入前</b>丢弃（解码层不必判 —— 判在唯一写入口即可）。</p>
     */
    public static final int MAX_TAG_NAME_LENGTH = 128;

    private CollectionBlockedLimits() {
    }

    /** 该集合是否已到达上限（{@code size >= max}）。{@code max <= 0} 视为「永远已满」。 */
    public static boolean isFull(final int size, final int max) {
        return max <= 0 || size >= max;
    }

    /**
     * 判断「把 {@code candidates} 里尚未存在的那几条加进 {@code current}」会不会超过 {@code max}。
     *
     * <p>语义细节（三条都影响「会不会误伤」）：</p>
     * <ul>
     *     <li>已存在的条目不占新名额 —— 对已满的名单重复阻塞同一件事必须继续成功（幂等），
     *     否则玩家点第二下会看到「名单已满」这种莫名其妙的话。</li>
     *     <li>{@code null} 条目忽略（解码层/界面都可能带 null，绝不能因此判满）。</li>
     *     <li>{@code remaining <= 0} 时仍允许「候选全都已存在」的情况通过，其余一律 false。</li>
     * </ul>
     *
     * @return {@code true} = 放得下（含「本来就都在」）；{@code false} = 会超上限，调用方必须
     *         <b>整体拒绝</b>（一格都不写），不能「能塞几条塞几条」—— 半写状态会让「我再点一次」
     *         的上限提示与玩家看到的名单对不上。
     */
    public static <T> boolean fits(final Set<T> current, final Iterable<T> candidates, final int max) {
        if (current == null || max <= 0) {
            return false;
        }
        if (candidates == null) {
            return current.size() <= max;
        }
        int remaining = max - current.size();
        for (final T candidate : candidates) {
            if (candidate == null || current.contains(candidate)) {
                continue;
            }
            if (remaining <= 0) {
                return false;
            }
            remaining--;
        }
        return true;
    }

    /**
     * 把 {@code candidates} 里能加的都加进 {@code current}，但不超过 {@code max}；返回<b>实际加入</b>的条数。
     *
     * <p>只在「上游已经用 {@link #fits} 判过、加得下」的路径上使用（例如界面自身的乐观更新、
     * 或测试）。服务端写入口一律<b>先 {@link #fits} 再整体写</b>，不要用本方法做「塞满为止」的降级 ——
     * 那会把「拒绝」变成「静默少几条」。</p>
     */
    public static <T> int addBounded(final Set<T> current, final Iterable<T> candidates, final int max) {
        if (current == null || candidates == null) {
            return 0;
        }
        int added = 0;
        for (final T candidate : candidates) {
            if (candidate == null || current.contains(candidate)) {
                continue;
            }
            if (isFull(current.size(), max)) {
                break;
            }
            current.add(candidate);
            added++;
        }
        return added;
    }

    /**
     * 取前 {@code max} 条（跳过 null）。委托 {@link ChargerFilterLimits#clamp}：
     * 「有界截断」的实现在全仓只有那一份，预分配容量取 {@code min(size, max)}，
     * 绝不按对端声明的数量分配内存。
     */
    public static <T> List<T> clamp(final Collection<T> source, final int max) {
        return ChargerFilterLimits.clamp(source, max);
    }

    /** 把任意输入洗成「可以落盘的标签列表」：跳过 null、跳过超长、去重、保序、条数不超过 {@link #MAX_TAGS_PER_PACKET}。 */
    public static List<String> sanitizeTagNames(final Collection<String> rawNames) {
        final List<String> out = new ArrayList<>();
        if (rawNames == null) {
            return out;
        }
        for (final String raw : rawNames) {
            if (raw == null || raw.isEmpty() || raw.length() > MAX_TAG_NAME_LENGTH) {
                continue;
            }
            if (!out.contains(raw)) {
                out.add(raw);
            }
            if (out.size() >= MAX_TAGS_PER_PACKET) {
                break;
            }
        }
        return out;
    }
}
