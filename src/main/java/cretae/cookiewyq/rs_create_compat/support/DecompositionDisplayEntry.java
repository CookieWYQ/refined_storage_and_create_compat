package cretae.cookiewyq.rs_create_compat.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一条「JEI 展示用的降解条目」—— 把 {@link DecompositionTable} 里的
 * {@link DecompositionProfile} 展开成「物品 id 字符串」的形状，专门给 JEI 分类页用。
 *
 * <h2>为什么不直接传 {@code DecompositionProfile} / {@code Item}</h2>
 * <p>本条目要<b>过网络</b>（服务端 → 客户端，见
 * {@code network/SyncDecompositionDisplayPacket}）。{@code Item} 是注册表对象，客户端只能用
 * 「注册 id 字符串」重新解析；把 id 先摊平成字符串，网络层与解析层就彻底解耦，
 * 也不会有「跨端手上拿着不同类型实例」的问题。</p>
 *
 * <h2>字段语义</h2>
 * <ul>
 *     <li><b>key</b>：条目的唯一键，直接来自数据包写法：精确物品条目 = 物品 id
 *     （{@code minecraft:oak_sapling}），tag 条目 = {@code #} + tag id
 *     （{@code #minecraft:leaves}）。同一条目在两端必须得到同一个 key（JEI 书签 / 反查靠它）。</li>
 *     <li><b>items</b>：这一条到底适用于哪些物品的<b>注册 id 列表</b>。精确条目只有一项；
 *     tag 条目是<b>服务端那一刻</b>展开出来的整组（懒匹配的展开结果，见 {@link DecompositionTable}）。
 *     展开为空（tag 里一个可解析物品都没有）时保留空列表 —— JEI 侧会跳过这条，但条目本身不丢。</li>
 *     <li><b>time / energy</b>：照抄 {@link DecompositionProfile#time()} / {@link DecompositionProfile#energy()}，
 *     <b>不含任何升级换算</b>（JEI 展示的是数据包写的原始值；速度升级只改耗时，见
 *     {@link DecompositionGeneratorSpec#effectiveTime(int, int)}）。</li>
 *     <li><b>results</b>：副产物（物品 id + 数量 + 概率），与数据包 {@code results} 逐项对应。</li>
 * </ul>
 *
 * <p><b>本类必须保持纯数据</b>：{@code support/} 包不允许出现客户端类型（专用服务器安全硬规则，
 * 见 {@code selfcheck_round47_dedicated_server_safety.py}）。因此这里只有 {@link String} / {@code int} / {@code float}。</p>
 *
 * @param key     唯一键（物品 id 或 {@code #tag id}）
 * @param items   适用物品的注册 id 列表（tag 条目 = 展开结果；不可变，不含 null）
 * @param time    降解 1 个物品所需 tick（数据包原值）
 * @param energy  降解完成时一次性写进缓存的 FE（数据包原值）
 * @param results 副产物列表（不可变，不含 null）
 */
public record DecompositionDisplayEntry(String key, List<String> items, int time, int energy,
                                        List<Result> results) {
    /** 归一化：null 列表 → 空列表；元素去 null；深拷贝成不可变，防止调用方改到"同一份数据"的另一处视图。 */
    public DecompositionDisplayEntry {
        key = key == null ? "" : key;
        items = items == null || items.isEmpty()
            ? List.of()
            : List.copyOf(nonNull(items));
        results = results == null || results.isEmpty()
            ? List.of()
            : List.copyOf(nonNull(results));
    }

    private static <T> List<T> nonNull(final List<T> source) {
        final List<T> copy = new ArrayList<>(source.size());
        for (final T element : source) {
            if (element != null) {
                copy.add(element);
            }
        }
        return copy;
    }

    /** 是不是 tag 条目（key 以 {@code #} 开头）。 */
    public boolean isTag() {
        return key.startsWith("#");
    }

    /** 平均功率（FE/t）：只用于界面展示文案，不参与任何记账（与 {@link DecompositionProfile#energyPerTick()} 同口径）。 */
    public double energyPerTick() {
        return time <= 0 ? 0.0D : (double) energy / (double) time;
    }

    /** 有没有副产物（没有 = 只发电）。 */
    public boolean hasResults() {
        return !results.isEmpty();
    }

    /**
     * 一条副产物：产出 {@code count} 个物品 id 为 {@code item} 的东西，概率 {@code chance}。
     *
     * <p>数值<b>不再裁剪</b>：它来自已经构造成功的 {@link DecompositionProfile.DecompositionResult}
     * （那边已经夹过区间），这里只是换个「物品 id」的写法过网，重复裁剪只会掩盖上游的问题。</p>
     */
    public record Result(String item, int count, float chance) {
        public Result {
            item = Objects.requireNonNullElse(item, "");
            count = Math.max(1, count);
            chance = Float.isNaN(chance) ? 0.0F : Math.max(0.0F, Math.min(1.0F, chance));
        }
    }
}
