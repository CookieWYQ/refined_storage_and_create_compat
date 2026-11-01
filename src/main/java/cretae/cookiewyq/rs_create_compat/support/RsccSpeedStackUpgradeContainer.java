package cretae.cookiewyq.rs_create_compat.support;

import cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 「只收速度升级 + 叠加升级」的 6 槽升级容器 + 第 ② 层（方块实体层）准入校验的<b>唯一实现</b>，
 * 同时是<b>新方块（网络资源接收器 / 请求器）节流的唯一数值来源</b>。
 *
 * <h2>为什么必须抽出来</h2>
 * <p>本模组新加的两个方块（网络资源接收器 / 请求器）的升级白名单<b>逐字相同</b>
 * （只 {@code speed_upgrade} + {@code stack_upgrade}，各最多 {@link UpgradeSlot#MAX_PER_UPGRADE} 个）。
 * 若各写一份，日后任何一方调上限都会造成「升级物品 tooltip 说 6、另一台机器只放 5」这类
 * <b>看不见的口径分裂</b>（设计文档 §3.2 坑 B 就是这一类问题的真实案例）。
 * 因此三个层次里的第 ② 层统一走这里：</p>
 * <ol>
 *     <li><b>第 ① 层（界面）</b>：{@code menu/UpgradeSlot} 的 {@code allowed} 白名单 + {@code maxPerUpgrade}；</li>
 *     <li><b>第 ② 层（方块实体兜底）</b>：{@link #validate()} —— 界面拦不住存档直改 / 版本变更 /
 *     管道灌入，这一层发现非法或超限一律<b>弹出到世界</b>（逐个 {@code dropOne}），<b>绝不销毁</b>；
 *     外加 {@link #getStackLimit}/{@link #getSlotLimit} <b>夹到 1</b> 的第一道防线（见下）；</li>
 *     <li><b>第 ③ 层（RS 升级注册表）</b>：{@code RegisterUpgradeDestinations} 注册本方块吃这两类升级
 *     （决定 RS 原生升级物品悬停提示里列出哪些机器）。</li>
 * </ol>
 *
 * <h2>R1（2026-10-17 收尾）：为什么要覆写 {@code getStackLimit} 且按「个数」计数</h2>
 * <p>与 {@code RangeChargerBlockEntity.java:111}（{@code getStackLimit}/{@code getSlotLimit}）与
 * {@code :354}（{@code validateUpgradeSlots}）<b>逐字对齐同一口径</b>，消掉新方块与已迁移机器之间的
 * 第二处不一致：</p>
 * <ul>
 *     <li><b>第一道防线（写入时）</b>：{@link ItemStackHandler} 默认放行 {@code getSlotLimit(slot)}
 *     —— 本版 NeoForge 是 <b>99</b>，于是漏斗 / 管道可以往一格塞几十个升级。覆写
 *     {@link #getStackLimit} 返回 {@code min(1, stack.getMaxStackSize())} 后，「一格多个」在
 *     <b>写入那一刻</b>就不可能发生（与界面 {@code UpgradeSlot.getMaxStackSize() = 1} 同一口径）。</li>
 *     <li><b>第二道防线（读档 / 直接 NBT 写入）</b>：{@link #countOf} 按<b>物品个数</b>累加
 *     （{@code count += stack.getCount()}），{@link #validate()} 也按个数保留前 {@code max} 个、
 *     其余<b>逐个</b>弹出。旧实现是 {@code count++} / {@code kept++}，<b>数槽不数个数</b> ——
 *     一格 64 个只算 1 个，上限被整条绕过（速率侧却按 64 算）。</li>
 * </ul>
 * <p><b>为什么要两道</b>：只有第二道时「上限」要靠每 tick 轮询才能生效（插件槽被塞满的那段时间
 * 数值已经在用了）；只有第一道时旧存档 / NBT 直写仍然能带进超限内容。两道都在，
 * 且都以 {@link UpgradeSlot#maxForPath(String)} 为唯一的上限来源。</p>
 *
 * <h2>为什么是 ItemStackHandler 而不是 SimpleContainer</h2>
 * <p>与 {@code RangeChargerBlockEntity} / {@code SchematicLoaderBlockEntity} 同一口径：
 * {@link ItemStackHandler} 的 {@code onContentsChanged} 是<b>任何写入</b>（点击 / Shift 移动 / 漏斗 / NBT 载入）
 * 的必经之路，因此第 ② 层校验能挂在「写入即校验」这一个点上，不需要另开 tick 轮询。</p>
 *
 * <h2>节流（速度 / 叠加升级 → 每 tick 批数 / 每批件数）</h2>
 * <p>设计文档 §1.4 第 2 条的口径是「<b>速度升级 = 每 tick 搬几批，叠加升级 = 每批搬多少</b>」。
 * 两个旋钮<b>只在 {@link #throttleBatchesPerTick()} / {@link #throttleBatch()} 两处给出</b>，
 * 界面与方块实体都读这里，绝不在别处再写一遍 {@code <<} 或 {@code *} 公式
 * （{@link RsccRateLimiter} 是唯一的换算工具；RS 的 {@code UpgradeContainer} ticker 的
 * {@code 9 - 2n} 在上限 6 时会算成负数，绝不复用 —— 见 {@link RsccRateLimiter} 类文档）。</p>
 *
 * <p><b>为什么不是「缩短间隔」</b>：间隔的物理下限就是 1 tick（每 tick 一次 {@code doWork}），
 * 所以「间隔」这个量<b>只能变慢、不能变快</b>；把速度升级做成 {@code intervalTicks(1, 档数)}
 * 会恒等于 1，即<b>速度升级一点效果都没有</b>（这正是本轮修掉的缺陷：界面文案承诺「每 tick 几批」，
 * 代码却每 tick 只搬一批）。要表达「更快」只剩一条路：<b>每 tick 多做几批</b>，于是有了
 * {@link #throttleBatchesPerTick()}。{@link #throttleIntervalTicks()} 因此被钉成恒等 1
 * （<b>只</b>用来表达「闸门每 tick 都放行」），不再是提速手段。</p>
 */
public final class RsccSpeedStackUpgradeContainer extends ItemStackHandler {
    /** 槽数：用户拍板 6。 */
    public static final int SLOTS = 6;
    /** {@code speed_upgrade} 的注册名 path。 */
    public static final String SPEED_PATH = "speed_upgrade";
    /** {@code stack_upgrade} 的注册名 path。 */
    public static final String STACK_PATH = "stack_upgrade";

    /**
     * 单批基础搬运量（件）。
     * <p>取 64（= 一组）而不是 1：本工程既有口径是「一组一组过」（{@code CollectionCacheBlockEntity}
     * 的 {@code batchSaturating(64L, …)}、{@code SchematicLoaderBlockEntity} 的每格 64），
     * 叠加升级在此基础上每级 +1 倍（{@code 64, 128, …, 448}），因此「一个一个过」=
     * <b>不装叠加升级</b>这条路要留给玩家自己选（装叠加升级就是一组一组过）。</p>
     */
    public static final long BASE_BATCH = 64L;

    /** 校验类：由宿主方块实体提供「弹出物品」与「落盘」两个动作。 */
    public interface Host {
        /**
         * 把物品弹出到世界（<b>绝不销毁</b>）。
         * <p>调用方通常会先判「{@code level != null && !level.isClientSide()}」——
         * 读档期与客户端镜像容器上都不可能、也不应该真的往世界里丢东西。</p>
         */
        void dropStack(ItemStack stack);

        /** 内容变化后标记方块实体已修改（让升级随 NBT 落盘）。 */
        void onUpgradesChanged();
    }

    private final Host host;
    /**
     * 本容器此刻能不能真的把物品弹到世界（宿主在构造时告知，<b>每次校验时现问</b>）。
     * <p><b>为什么是「现问」而不是构造时定死的布尔</b>：方块实体在<b>构造期必然还没有 level</b>
     * （真正的服务端世界由 {@code setLevel} 稍后注入），若构造时定死就只能是 false，
     * 于是 {@link #validate()} 永远不生效、第 ② 层形同虚设。判据交给宿主以 lambda 给出
     * （{@code () -> getLevel() != null && !getLevel().isClientSide()}），与
     * {@code RangeChargerBlockEntity#validateUpgradeSlots} 的
     * 「{@code level == null || level.isClientSide()} 直接 return」<b>同一时刻、同一判据</b>。</p>
     * <p><b>为什么不直接在容器里判 {@code Level}</b>：{@code support/} 包不得引入任何可能加载
     * 客户端类的类型（专服安全硬规则，见 {@code selfcheck_round47_dedicated_server_safety.py}），
     * 而 {@code Level.isClientSide()} 的判定正是宿主的职责。</p>
     */
    private final java.util.function.BooleanSupplier canDropToWorld;
    /** 防止 {@link #validate()} 在弹出物品（触发 {@code onContentsChanged}）时重入。 */
    private boolean validating;
    /** 读档期间不校验（历史存档里可能有当时合法、现在非法的内容，等读完后统一校验一次）。 */
    private boolean loading;
    /**
     * 节流缓存：-1 = 无效。
     * <p><b>为什么缓存</b>：{@link #throttleBatchesPerTick()} / {@link #throttleBatch()} 会被
     * <b>每 tick</b> 读一次（{@code doWork}），而 {@link #kindOf} 每次都要查一次
     * {@link BuiltInRegistries#ITEM} 注册表（6 次槽位读取 + 6 次注册表查询）。升级内容只在
     * {@code onContentsChanged} 时变，因此「变了才算」比「每 tick 都算」省掉这份固定开销。</p>
     */
    private int cachedBatches = -1;
    private long cachedBatch = -1L;

    /**
     * 构造一个<b>可以</b>把非法 / 超限升级弹出到世界的容器（服务端方块实体用）。
     * <p>等价于 {@code new RsccSpeedStackUpgradeContainer(host, () -> true)}；保留这个构造器是为了
     * 让「宿主自己保证有服务端世界」这条主路径读起来最短。</p>
     */
    public RsccSpeedStackUpgradeContainer(final Host host) {
        this(host, () -> true);
    }

    /**
     * @param canDropToWorld 每次校验时现问的「此刻能不能往世界丢东西」判据；
     *                       客户端镜像容器传 {@code () -> false}，方块实体传
     *                       {@code () -> getLevel() != null && !getLevel().isClientSide()}。
     *                       为 false 时 {@link #validate()} <b>一个物品都不动</b>
     *                       （与 {@code RangeChargerBlockEntity#validateUpgradeSlots} 的
     *                       「{@code level == null || level.isClientSide()} 直接 return」同一语义）。
     */
    public RsccSpeedStackUpgradeContainer(final Host host,
                                          final java.util.function.BooleanSupplier canDropToWorld) {
        super(SLOTS);
        this.host = host;
        this.canDropToWorld = canDropToWorld;
    }

    /**
     * <b>每格最多 1 个升级</b>（与界面 {@link UpgradeSlot} 的 {@code getMaxStackSize() = 1} 同一口径）。
     *
     * <p>逐字对齐 {@code RangeChargerBlockEntity.java:111}：{@code ItemStackHandler} 的默认实现放行
     * {@code getSlotLimit(slot)}（本版 NeoForge 是 <b>99</b>），于是漏斗 / 管道可以往一格塞几十个升级，
     * 而速率侧按个数累加 ⇒ 升级上限被直接绕过。夹到 1 之后「一格多个」在<b>写入时</b>就不可能发生。</p>
     */
    @Override
    protected int getStackLimit(final int slot, final ItemStack stack) {
        return Math.min(1, stack.getMaxStackSize());
    }

    /** 同上：对外暴露的槽上限也必须是 1（菜单 / 任何读 {@code IItemHandler} 的调用方都看得到）。 */
    @Override
    public int getSlotLimit(final int slot) {
        return 1;
    }

    /**
     * 是否只接受速度 / 叠加升级的<b>唯一判据</b>。
     * <p>按「RS 命名空间 + 注册名 path」判定，<b>不</b>按 Java 类：RS 2.0.9 里速度 / 叠加 / 丝触 /
     * 自动合成升级都是同一个 {@code SimpleUpgradeItem} 类，用 {@code instanceof} 根本区分不开
     * （设计文档 §4.1）。</p>
     *
     * @return {@link #SPEED_PATH} / {@link #STACK_PATH} / {@code null}（= 非本容器接受的升级）
     */
    @javax.annotation.Nullable
    public static String kindOf(final ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        final ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null || !"refinedstorage".equals(id.getNamespace())) {
            return null;
        }
        return switch (id.getPath()) {
            case SPEED_PATH -> SPEED_PATH;
            case STACK_PATH -> STACK_PATH;
            default -> null;
        };
    }

    /**
     * 指定种类的升级总<b>个数</b>（不是槽位数）。
     * <p>与 {@code RangeChargerBlockEntity#countUpgrades} 的 {@code count += stack.getCount()} 同一口径，
     * 也与 {@link #validate()} 的「按个数保留 / 逐个弹出」同一口径 —— 计数与清退必须同源，
     * 否则「清退认为只有 1 个、速率认为有 64 个」这条 R1 缺陷会原样复现。</p>
     */
    public int countOf(final String path) {
        int count = 0;
        for (int i = 0; i < getSlots(); i++) {
            final ItemStack inSlot = getStackInSlot(i);
            if (path.equals(kindOf(inSlot))) {
                count += inSlot.getCount();
            }
        }
        return count;
    }

    /** 速度升级数（节拍用；上限 {@link UpgradeSlot#MAX_PER_UPGRADE} 由 {@link #validate()} 保证）。 */
    public int speedCount() {
        return countOf(SPEED_PATH);
    }

    /** 叠加升级数（单批搬运量用）。 */
    public int stackCount() {
        return countOf(STACK_PATH);
    }

    /**
     * 节拍间隔（tick）：<b>恒等于 1</b>（闸门每 tick 都放行）。
     *
     * <p><b>为什么钉成 1</b>：间隔的物理下限就是 1 tick —— 每个 tick 方块实体最多被 {@code doWork}
     * 调用一次，所以「间隔」<b>只能表达变慢，不能表达变快</b>。以前这里写的是
     * {@code RsccRateLimiter.intervalTicks(1, 档数)}，而 {@code intervalTicks(base=1, n)} 恒等于
     * {@code max(1, 1 >> n) == 1} ⇒ 速度升级对节拍<b>毫无作用</b>，可界面文案却在承诺
     * 「每 tick 几批」。要表达「更快」只能加批数，见 {@link #throttleBatchesPerTick()}。</p>
     *
     * <p>保留这个方法而不是删掉：两个方块实体的 {@code throttleTick()} 闸门仍在用它，
     * 而「每 tick 都放行」依旧是正确语义；只是它<b>不再</b>是提速旋钮，也不必再参与任何
     * 升级换算（写死 1，比每次算一遍 {@code intervalTicks} 更不容易被误读成「节拍」）。</p>
     */
    public int throttleIntervalTicks() {
        return 1;
    }

    /**
     * 每 tick 的批数（= 速度升级旋钮）：{@code 1 << level(速度数)} ⇒ <b>1, 2, 4, 8, 16, 32, 64</b>。
     *
     * <p><b>换算只走 {@link RsccRateLimiter}</b>：{@link RsccRateLimiter#level(int)} 把漏斗 / NBT
     * 直写塞进来的 64 个升级夹到 {@link RsccRateLimiter#MAX_LEVEL}，
     * {@link RsccRateLimiter#shiftSaturating(int, int)} 做饱和移位 ⇒ ① 永不非正、② 没有除法、
     * ③ 不会溢出、④ 上限来源只有一处。与「统一曲线 = 每级 ×2」同口径
     * （叠加升级<b>不</b>混进这里：它只作用在 {@link #throttleBatch()} 的批量上，
     * 否则「叠加 = 每批多少」这条承诺会被破坏）。</p>
     */
    public int throttleBatchesPerTick() {
        if (cachedBatches < 0) {
            cachedBatches = RsccRateLimiter.shiftSaturating(1, RsccRateLimiter.level(speedCount()));
        }
        return cachedBatches;
    }

    /** 单批搬运量（件）：叠加升级每级 +1 倍（{@code 64, 128, …, 448}），走饱和乘法（I1 / I3）。 */
    public long throttleBatch() {
        if (cachedBatch < 0L) {
            cachedBatch = RsccRateLimiter.batchSaturating(BASE_BATCH, stackCount());
        }
        return cachedBatch;
    }

    /**
     * 等效升级档数 = {@code level(速度) + level(叠加) * 3}，<b>只用于界面显示「等效档数」这一个数据槽</b>。
     *
     * <p>与范围充电器 / 两个定量保持器的口径一致（{@link RsccRateLimiter#power(int, int)}），
     * 于是「1 个叠加升级 = 3 个速度升级」这条工程既有约定在新方块的<b>显示</b>上同样成立。
     * <b>不要用它算吞吐</b>：本机的实际吞吐是两个独立旋钮
     * ——{@link #throttleBatchesPerTick()}（只看速度）与 {@link #throttleBatch()}（只看叠加），
     * 把叠加再算进档数会让「叠加 = 每批多少」被算两遍。</p>
     */
    public int throttlePacing() {
        return RsccRateLimiter.power(speedCount(), stackCount());
    }

    /** 内容变了：先让节流缓存失效，再走既有校验（顺序不能反，否则校验里读到的还是旧值）。 */
    @Override
    protected void onContentsChanged(final int slot) {
        super.onContentsChanged(slot);
        cachedBatches = -1;
        cachedBatch = -1L;
        host.onUpgradesChanged();
        if (!validating && !loading) {
            validate();
        }
    }

    /** 读档前调用：读档期间的中间状态不触发校验（读完由 {@link #finishLoading()} 统一校验一次）。 */
    public void beginLoading() {
        loading = true;
    }

    /** 读档后调用：统一做一次校验（历史存档里的非法 / 超限升级在这一刻被弹出）。 */
    public void finishLoading() {
        loading = false;
        validate();
    }

    /**
     * 第 ② 层校验：非法升级逐个弹出 + 单种超限逐个弹出，<b>按物品个数计数</b>。
     *
     * <p><b>逐字对齐</b> {@code RangeChargerBlockEntity.java:354} 的 {@code validateUpgradeSlots}：
     * 「没有服务端世界就不动任何东西」的前置 + 「保留排在前面的 N 个、其余逐个弹出到世界」，
     * 只是清理种类少了两类（本容器只有速度 / 叠加，没有范围 / 创造范围）。</p>
     */
    public void validate() {
        // 与本类其它校验同一前置条件：没有服务端世界就没法「弹出到世界」，此时绝不删东西
        // （客户端镜像容器走的就是这一条 —— 它照常会调 validate()，但这里直接返回）。
        if (validating || !canDropToWorld.getAsBoolean()) {
            return;
        }
        validating = true;
        try {
            // 1) 非法升级：整格弹出（一格可能被塞了多个，逐个抽空；不做任何销毁）
            //    循环次数由「当前个数」界定（不用 while(!isEmpty())：万一同格数量不再减少也不会死循环）
            for (int i = 0; i < getSlots(); i++) {
                final ItemStack inSlot = getStackInSlot(i);
                if (inSlot.isEmpty() || kindOf(inSlot) != null) {
                    continue;
                }
                for (int n = inSlot.getCount(); n > 0; n--) {
                    dropOne(i);
                }
            }
            // 2) 单种上限：按「物品个数」计（与 countOf 的 += stack.getCount() 同一口径）
            dropBeyondCap(SPEED_PATH);
            dropBeyondCap(STACK_PATH);
        } finally {
            validating = false;
        }
    }

    /**
     * 弹出同一种升级中超出 {@link UpgradeSlot#maxForPath(String)} 的那些（保留排在前面的）。
     * <p><b>与旧写法的唯一区别</b>：旧写法是 {@code kept++}（一格算一个 ⇒ 一格 64 个全放行），
     * 现在是「该格还能留几个：先到先得，余量逐个弹出」（{@code allowed = max - kept}），
     * 与 {@code RangeChargerBlockEntity} 的写法逐字同构。</p>
     */
    private void dropBeyondCap(final String path) {
        final int max = UpgradeSlot.maxForPath(path);
        int kept = 0;
        for (int i = 0; i < getSlots(); i++) {
            final ItemStack inSlot = getStackInSlot(i);
            if (!path.equals(kindOf(inSlot))) {
                continue;
            }
            // 该格还能留几个：先到先得（保留排在前面的），余量逐个弹出
            final int inSlotCount = inSlot.getCount();
            final int allowed = Math.max(0, max - kept);
            for (int excess = inSlotCount - allowed; excess > 0; excess--) {
                dropOne(i);
            }
            kept += Math.min(inSlotCount, allowed);
        }
    }

    /**
     * 从 {@code slot} 抽出 <b>1 个</b>物品并弹出到世界（名字如实：不叫 dropAndClear，因为它绝不清空整格）。
     * <p>调用方负责循环（一格可能有多个）。{@link #validate()} 已保证此时 {@code canDropToWorld} 为真
     * 且为非空栈，因此这里绝不会把物品凭空销毁。</p>
     * <p>与 {@code RangeChargerBlockEntity#dropOne} 同义：每次只抽 1 个，逐个交给宿主的
     * {@link Host#dropStack}（宿主自己再判一次世界写入）。</p>
     */
    private void dropOne(final int slot) {
        final ItemStack removed = extractItem(slot, 1, false);
        if (!removed.isEmpty()) {
            host.dropStack(removed);
        }
    }

    /** 便捷构造：把「弹出到世界」这一动作直接落到世界坐标上（各机器共用同一段代码）。 */
    public static void dropIntoWorld(final net.minecraft.world.level.Level level,
                                     final net.minecraft.core.BlockPos pos,
                                     final ItemStack stack) {
        if (stack.isEmpty() || level == null || level.isClientSide()) {
            return;
        }
        final ItemEntity entity = new ItemEntity(level,
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
        entity.setDeltaMovement(0, 0.1, 0);
        level.addFreshEntity(entity);
    }
}
