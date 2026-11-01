package cretae.cookiewyq.rs_create_compat.support;

/**
 * 第 123 轮「降解室发电机重做」的<b>唯一数值来源</b>：库存布局、界面几何、升级换算三件事，
 * 全部只在这里写一遍。
 *
 * <h2>为什么要单独抽一个 Spec 类</h2>
 * <p>重做把界面从「256 格 / 5 页」换成「16 格输入 + 4 格副产物 + 6 格升级 + 1 条能量条」，
 * 于是<b>库存下标</b>（方块实体）、<b>槽位坐标</b>（Menu）、<b>贴图槽位网格</b>
 * （{@code tools/make_gui_bg.py} 的 {@code --slots} 参数）、<b>屏幕文案区</b>（Screen）
 * 四份数据必须逐像素 / 逐下标一致。历史上它们各写一份的结果就是「背景画在 A 处、槽落在 B 处」
 * 与「声明 100 槽、实际 91 槽」（第 122 轮 Shift 点击崩溃）。
 * 因此这里把四份数据钉死成一份，任何一方只许<b>引用</b>，不许再写数字。</p>
 *
 * <h2>库存布局（下标 → 语义）</h2>
 * <pre>
 * 0 .. 15   16 格输入（4 列 × 4 行，只收可降解物）
 * 16 .. 19   4 格副产物输出（2 列 × 2 行，可抽不可入）
 * </pre>
 * <p>升级槽<b>不在</b>物品库存里：它由 {@link RsccSpeedStackUpgradeContainer} 单独持有
 * （6 格 = 3 列 × 2 行，只收 speed_upgrade / stack_upgrade）。</p>
 *
 * <h2>界面几何（Menu 坐标系，Screen 与贴图生成脚本共用）</h2>
 * <pre>
 * +--------------------------------------------------+  PANEL_WIDTH = 182
 * | 标题                                              |
 * |  ┌──┬──┬──┬──┐   ┌──┐          ┌──────┐          |  y = 18
 * |  ├──┼──┼──┼──┤   │  │          │      │          |
 * |  ├──┼──┼──┼──┤ 熔炉 │  │  能量条 │      │          |  ARROW (86,45) 24×16
 * |  └──┴──┴──┴──┘ 箭头 └──┘          │      │          |
 * |                                                    |
 * |            ┌──┬──┬──┐                              |  UPGRADE (118,76) 3 列 × 2 行
 * |            ├──┼──┼──┤                              |
 * |            └──┴──┴──┘                              |
 * |  玩家背包 3 × 9                                     |  PLAYER_INV (8,128)
 * |  快捷栏 1 × 9                                       |  HOTBAR (8,188)
 * +--------------------------------------------------+  PANEL_HEIGHT = 212
 * </pre>
 * <p><b>第 126 轮删除了「状态 / 缓存 / 升级」三行文字</b>（用户原话「这三行文本全部删除，因为这三行都是废话」），
 * 因此 {@code TEXT_*} 那五个常量已不存在；升级槽左侧那片空白现在<b>不放任何固定读数</b>，
 * 进度读数只出现在箭头上方（{@code progress_short}，且带 hover tooltip）。</p>
 *
 * <h2>坐标语义（贴图 vs Menu，写错就是「槽位比背景偏 1px」）</h2>
 * <p>本类的所有槽位坐标都是 <b>Menu 坐标</b>（即 {@code addSlot} 的 x/y）。MC 的 18×18 槽框背景
 * 左上角在 {@code (Slot.x - 1, Slot.y - 1)}，所以生成贴图时必须用
 * {@code tools/make_gui_bg.py --slots-menu}（工具内部自动 -1），<b>不许</b>手算。
 * 第 126 轮之前的 {@code decomposition_generator.png} 正是把 Menu 坐标直接当精灵坐标贴，
 * 整幅槽框右下偏 1px —— 现在由 {@code tools/selfcheck_gui_slot_alignment.py} 全量盯住。
 * </pre>
 *
 * <h2>升级换算（本类只给公式，调用点是方块实体）</h2>
 * <ul>
 *     <li><b>速度升级</b>：{@link #effectiveTime(int, int)} —— 耗时右移（{@code base >> n}），
 *     <b>单件发电量不变</b>（用户原话「增加降解速度但是不改变发电量」），因此 FE/t 随升级上升；</li>
 *     <li><b>堆叠升级</b>：{@link #inputSlotLimit(int)} —— 每格上限 {@code 64 * (1 + n)}
 *     （与 {@link RsccRateLimiter#batchSaturating} 同一口径，与采集仓 / 接收器的叠加升级语义一致）。</li>
 * </ul>
 *
 * <p><b>本类必须保持纯常量 / 纯算术</b>：{@code support/} 包不得引入任何可能加载客户端类的类型
 * （专服安全硬规则，见 {@code selfcheck_round47_dedicated_server_safety.py}）。</p>
 */
public final class DecompositionGeneratorSpec {
    private DecompositionGeneratorSpec() {
    }

    // ==================== ① 库存布局 ====================

    /** 输入格列数。 */
    public static final int INPUT_COLS = 4;
    /** 输入格行数。 */
    public static final int INPUT_ROWS = 4;
    /** 输入格数（16）。 */
    public static final int INPUT_COUNT = INPUT_COLS * INPUT_ROWS;
    /** 副产物格列数。 */
    public static final int OUTPUT_COLS = 2;
    /** 副产物格行数。 */
    public static final int OUTPUT_ROWS = 2;
    /** 副产物格数（4）。 */
    public static final int OUTPUT_COUNT = OUTPUT_COLS * OUTPUT_ROWS;
    /** 输入区在库存里的起点下标。 */
    public static final int INPUT_FIRST = 0;
    /** 副产物区在库存里的起点下标（= 16）。 */
    public static final int OUTPUT_FIRST = INPUT_FIRST + INPUT_COUNT;
    /** 物品库存总格数（20）；升级槽不在此列。 */
    public static final int INVENTORY_SIZE = INPUT_COUNT + OUTPUT_COUNT;

    /** 未装堆叠升级时每格输入上限。 */
    public static final int INPUT_BASE_LIMIT = 64;
    /** 副产物格上限（固定 64，不受堆叠升级影响）。 */
    public static final int OUTPUT_LIMIT = 64;

    /** 升级槽列数。 */
    public static final int UPGRADE_COLS = 3;
    /** 升级槽行数。 */
    public static final int UPGRADE_ROWS = 2;
    /** 升级槽数（6）。 */
    public static final int UPGRADE_SLOTS = UPGRADE_COLS * UPGRADE_ROWS;

    // ==================== ② 界面几何 ====================

    /** 面板宽。 */
    public static final int PANEL_WIDTH = 182;
    /** 面板高。 */
    public static final int PANEL_HEIGHT = 212;
    /** 槽位间距（原版 18）。 */
    public static final int SLOT_PITCH = 18;

    /** 输入区第一格左上角 x。 */
    public static final int INPUT_X = 8;
    /** 输入区第一格左上角 y。 */
    public static final int INPUT_Y = 18;

    /** 熔炉箭头（原版 furnace.png 的 24×16 进度箭头）左上角 x。 */
    public static final int ARROW_X = 86;
    /** 熔炉箭头左上角 y（输入区竖直中心 ≈ 53）。 */
    public static final int ARROW_Y = 45;
    /** 熔炉箭头宽（原版 sprite 尺寸，不许改）。 */
    public static final int ARROW_WIDTH = 24;
    /** 熔炉箭头高（原版 sprite 尺寸，不许改）。 */
    public static final int ARROW_HEIGHT = 16;

    /** 副产物区第一格左上角 x。 */
    public static final int OUTPUT_X = 118;
    /** 副产物区第一格左上角 y。 */
    public static final int OUTPUT_Y = 37;

    /** 能量条内容区左上角 x（宽固定 16，见 RsccEnergyBar.WIDTH）。 */
    public static final int ENERGY_X = 158;
    /** 能量条内容区左上角 y。 */
    public static final int ENERGY_Y = 18;
    /** 能量条高（与范围充电器同款 51）。 */
    public static final int ENERGY_HEIGHT = 51;

    /** 升级槽第一格左上角 x。 */
    public static final int UPGRADE_X = 118;
    /** 升级槽第一格左上角 y。 */
    public static final int UPGRADE_Y = 76;

    /**
     * 内容区左右留白（标题宽度 = 面板宽 - 2 × 本值）。
     * <p>第 126 轮删掉「状态 / 缓存 / 升级」三行文字后，原来的 {@code TEXT_X} / {@code TEXT_Y} /
     * {@code TEXT_LINE_STEP} / {@code TEXT_MAX_WIDTH} / {@code TEXT_LINES} 五个常量一并删除
     * （用户原话：「这三行文本全部删除，因为这三行都是废话」）。现在只留下标题需要的这一份留白。</p>
     */
    public static final int CONTENT_INSET = 8;

    /** 玩家主背包行数（原版 3 行）。 */
    public static final int PLAYER_INV_ROWS = 3;
    /** 每行槽数（主背包与快捷栏都是 9）。 */
    public static final int PLAYER_COLS = 9;
    /** 玩家主背包槽数（27）。 */
    public static final int PLAYER_MAIN_COUNT = PLAYER_INV_ROWS * PLAYER_COLS;
    /** 快捷栏槽数（9）。 */
    public static final int PLAYER_HOTBAR_COUNT = PLAYER_COLS;
    /**
     * 玩家区槽数 = <b>27 + 9 = 36</b>。
     * <p>原版 {@code Inventory} 一共只有 36 格（主背包 27 + 快捷栏 9）。第 122 轮的实机崩溃
     * 就是因为这里被写成 {@code 36 + 9 = 45}（把「36 格总数」又加了一遍快捷栏）——
     * 因此这个常量<b>只许由行列推导</b>，绝不再手写数字。</p>
     */
    public static final int PLAYER_SLOT_COUNT = PLAYER_MAIN_COUNT + PLAYER_HOTBAR_COUNT;

    /** 玩家主背包第一行左上角 x。 */
    public static final int PLAYER_INV_X = 8;
    /** 玩家主背包第一行左上角 y。 */
    public static final int PLAYER_INV_Y = 128;
    /** 快捷栏左上角 y。 */
    public static final int HOTBAR_Y = 188;

    // ==================== ③ 升级换算 ====================

    /**
     * 装了 {@code speedUpgrades} 个速度升级后，1 个物品实际需要的 tick 数。
     *
     * <p>= {@link RsccRateLimiter#intervalTicks(int, int)}：{@code max(1, base >> level(n))}。
     * <b>单件发电量不随速度升级变化</b>（用户口径），所以这条只影响「多快降解完」。</p>
     *
     * @param baseTime     数据包里写的 {@code time}（tick）
     * @param speedUpgrades 速度升级个数（任何来源的越界值都由 RsccRateLimiter 收敛）
     */
    public static int effectiveTime(final int baseTime, final int speedUpgrades) {
        return RsccRateLimiter.intervalTicks(baseTime, speedUpgrades);
    }

    /**
     * 装了 {@code stackUpgrades} 个堆叠升级后，每格输入的上限（件）。
     *
     * <p>= {@code 64 * (1 + level(n))}（{@link RsccRateLimiter#batchSaturating}），
     * 与采集仓 / 网络资源接收器的叠加升级语义<b>逐字一致</b>：满级 6 个 ⇒ 每格 448 件。
     * 上限再夹到 {@link Integer#MAX_VALUE} 由 {@code batchSaturating} 自己保证（long → int 饱和）。</p>
     *
     * @param stackUpgrades 堆叠升级个数
     */
    public static int inputSlotLimit(final int stackUpgrades) {
        final long limit = RsccRateLimiter.batchSaturating(INPUT_BASE_LIMIT, stackUpgrades);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(INPUT_BASE_LIMIT, limit));
    }

    /** 输入区第 {@code index}（0..15）格在库存里的下标。 */
    public static int inputSlot(final int index) {
        return INPUT_FIRST + index;
    }

    /** 副产物区第 {@code index}（0..3）格在库存里的下标。 */
    public static int outputSlot(final int index) {
        return OUTPUT_FIRST + index;
    }

    /** 这个库存下标是不是输入格。 */
    public static boolean isInputSlot(final int slot) {
        return slot >= INPUT_FIRST && slot < INPUT_FIRST + INPUT_COUNT;
    }

    /** 这个库存下标是不是副产物格。 */
    public static boolean isOutputSlot(final int slot) {
        return slot >= OUTPUT_FIRST && slot < OUTPUT_FIRST + OUTPUT_COUNT;
    }
}
