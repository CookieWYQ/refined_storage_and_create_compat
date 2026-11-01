package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Objects;

/**
 * 一条「可降解」配置：把 1 个该物品降解掉要花多久（{@code time} tick）、发多少电（{@code energy} FE）、
 * 以及到点后要掷骰产出的<b>副产物列表</b>（{@code results}）。
 *
 * <p><b>为什么带 Item 而不带 ItemStack</b>：副产物只按「物品 + 数量 + 概率」描述，不涉及 NBT / 损坏值；
 * 同一个数值组合会被大量物品共享（例如所有小件植物都是 10 tick / 25 FE），让 record 只描述「数值」
 * 可以让 {@link DecompositionTable} 用一份实例喂给很多键，也让「时间/发电量必须为正」这条不变量
 * 只有一个落点。</p>
 *
 * <h2>第 123 轮新增：{@code results}（副产物）</h2>
 * <p>数据包里的写法见 {@link DecompositionTable} 类注释；这里只钉语义：</p>
 * <ul>
 *     <li>每一项都是<b>独立掷骰</b>：用服务端 {@code level.random} 判 {@code random < chance}，
 *     命中才产出 {@code count} 个 {@code item}。因此同一条可以有多个产物，也可以一个都不出；</li>
 *     <li>{@code results} 为空 = <b>只有电、没有副产物</b>（旧版条目就是这种形态）；</li>
 *     <li>列表不可变、不含 null：机器侧可以放心遍历。</li>
 * </ul>
 *
 * <h2>越界值：裁剪，不抛异常</h2>
 * <p>数据包是「别人写的」，一个笔误不该让整台机器 / 整个重载炸掉。因此本 record 的构造器
 * 对 {@code time} / {@code energy} / {@code count} / {@code chance} 一律<b>夹到合法区间</b>
 * （{@link #MIN_TIME} … {@link #MAX_TIME}、1 … {@link #MAX_ENERGY}、{@link #MIN_COUNT} … {@link #MAX_COUNT}、
 * {@link #MIN_CHANCE} … {@link #MAX_CHANCE}），不抛 {@link IllegalArgumentException}。
 * 「裁剪发生过几次」由 {@link DecompositionTable} 在解析时统计并写进重载汇总
 * （{@link DecompositionTable.LoadReport#clamped()}），这样作者能从一条日志里知道数据包写超了。</p>
 *
 * @param time    降解 1 个物品所需的 tick 数（夹到 {@value #MIN_TIME} … {@value #MAX_TIME}）
 * @param energy  降解完成时一次性写进本机缓存的 FE（夹到 1 … {@value #MAX_ENERGY}）
 * @param results 到点后独立掷骰的副产物列表（不可变；空 = 只有电）
 */
public record DecompositionProfile(int time, int energy, List<DecompositionResult> results) {
    /** 允许的最小耗时（0 会让进度条永远满、每 tick 发一次电）。 */
    public static final int MIN_TIME = 1;
    /** 耗时上限（10 分钟）：数据包写错一个 0 不要让机器看起来「永远转不完」。 */
    public static final int MAX_TIME = 20 * 60 * 10;
    /** 单次发电上限（1 亿 FE）：防止数据包笔误把缓存瞬间灌满。 */
    public static final int MAX_ENERGY = 100_000_000;
    /** 单个副产物的最小数量（0 个 = 这条产物没有意义，夹到 1；要「不产出」请用 chance 表达）。 */
    public static final int MIN_COUNT = 1;
    /** 单个副产物的最大数量（与物品栈上限一致；超了没有意义且会让输出格一次塞不下）。 */
    public static final int MAX_COUNT = 64;
    /** 最小概率（0 = 永远不出）。 */
    public static final float MIN_CHANCE = 0.0F;
    /** 最大概率（1 = 必定出）。 */
    public static final float MAX_CHANCE = 1.0F;

    public DecompositionProfile {
        time = clamp(time, MIN_TIME, MAX_TIME);
        energy = clamp(energy, 1, MAX_ENERGY);
        // null / 空列表统一成 List.of()：读方不需要判 null；同时滤掉 null 元素（坏数据包不该让机器 NPE）
        results = results == null || results.isEmpty()
            ? List.of()
            : results.stream().filter(Objects::nonNull).toList();
    }

    /**
     * 兼容旧调用（第 101–122 轮只有时长的两参写法）：等价于「没有副产物、只有电」。
     * <p>保留它是因为旧数据包 / 旧代码 / 旧自检都可能还按两参构造；数据包里的
     * {@code results} 本来就是可选字段。</p>
     */
    public DecompositionProfile(final int time, final int energy) {
        this(time, energy, List.of());
    }

    /** 平均发电功率（FE/t），只用于界面 / 文案展示，不参与任何记账。 */
    public double energyPerTick() {
        return (double) energy / (double) time;
    }

    /** 是否有副产物（没有 = 只发电）。避免调用方到处写 {@code !profile.results().isEmpty()}。 */
    public boolean hasResults() {
        return !results.isEmpty();
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 一条副产物：命中 {@code chance} 时产出 {@code count} 个 {@code item}。
     *
     * <p>构造时就夹好区间（见 {@link DecompositionProfile} 类注释）：{@code count} ∈
     * [{@value DecompositionProfile#MIN_COUNT}, {@value DecompositionProfile#MAX_COUNT}]，
     * {@code chance} ∈ [{@value DecompositionProfile#MIN_CHANCE}, {@value DecompositionProfile#MAX_CHANCE}]。
     * {@code chance} 为 {@link Float#NaN}（宽松 Gson 可能把裸 {@code NaN} 读进来）时按 0 处理 ——
     * 「永远不出」比「永远出」安全，且与「坏值只影响自己」的口径一致。</p>
     *
     * @param item   产物物品（非 null）
     * @param count  每次命中的数量（夹到 1 … 64）
     * @param chance 命中概率（夹到 0 … 1）
     */
    public record DecompositionResult(Item item, int count, float chance) {
        public DecompositionResult {
            // 只有「解析出真实 Item」才会走到这里（坏 id 在 DecompositionTable 里就被跳过了），
            // 因此这里的 requireNonNull 是防御性的、不会成为数据包容错的失败点。
            item = Objects.requireNonNull(item, "item");
            count = clampCount(count);
            chance = clampChance(chance);
        }

        private static int clampCount(final int value) {
            return Math.max(MIN_COUNT, Math.min(MAX_COUNT, value));
        }

        private static float clampChance(final float value) {
            if (Float.isNaN(value)) {
                return MIN_CHANCE;
            }
            return Math.max(MIN_CHANCE, Math.min(MAX_CHANCE, value));
        }
    }
}
