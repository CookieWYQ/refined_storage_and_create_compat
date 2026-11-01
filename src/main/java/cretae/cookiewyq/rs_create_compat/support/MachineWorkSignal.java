package cretae.cookiewyq.rs_create_compat.support;

/**
 * <b>「输出总线正对着的机器正在工作」这条判据的节奏常量与判定式（唯一真源）</b>。
 *
 * <h2>为什么需要它（用户原话，逐字）</h2>
 * <p><i>「我要求的就是他能保持每时每刻，只要有机械手或者之类的机器……就是识别到它正在工作，
 * 那么它应该【全速运转】。就是不要出现什么金板放在那里，然后我机械手啥也不动，就那么干瞪着，
 * 然后过了好一会儿才开始缓过来那样子。」</i></p>
 *
 * <p>改前，补合成请求的评估节流是
 * {@code SequenceExecutionChamberBlockEntity.BUS_AUTOCRAFT_COOLDOWN_TICKS = 40}，而这个计数是
 * <b>「每调用一次减一」</b>，调用点又在引擎节流（{@code ENGINE_INTERVAL_TICKS = 5} tick）之后 ——
 * 于是真实的评估周期是 <b>40 × 5 = 200 tick = 10 秒</b>，而确定性配方在默认档下一次只请求
 * <b>1 份</b>。机器自己的加工周期是 0.5~1 秒，因此即使一切正常，产线也只能跑到「1 件 / 10 秒」
 * —— 这就是「过了好一会儿才开始缓过来」与「金板放在那里干瞪眼」的量化来源。</p>
 *
 * <h2>本类的判据（两档节奏，事件驱动，不是定时器强行重发）</h2>
 * <ul>
 *     <li><b>热档</b>（{@link #HOT_COOLDOWN_RUNS} = 0）：一旦「机器正在工作」成立，请求评估
 *     <b>每个引擎节拍都跑一次</b>（= 5 tick = 0.25 秒），仍然<b>只在缺口 &gt; 0 时</b>才发请求；</li>
 *     <li><b>冷档</b>（{@link #COLD_COOLDOWN_RUNS} = 40）：没有任何机器在工作时，保持改动前的
 *     自愈节拍（200 tick）。这时本来就没有「该供的料」，多评估只是白烧 CPU；</li>
 *     <li><b>宽限</b>（{@link #GRACE_TICKS}）：机器把一件料吃掉的同一帧，工位上可能短暂看不到在制件。
 *     一次「看见在制件」之后 {@value #GRACE_TICKS} tick 内仍按热档处理，避免在一步加工的中间
 *     掉回冷档（否则又变成「等 10 秒」）。</li>
 * </ul>
 *
 * <h2>成本（为什么会话里敢在每个引擎节拍评估）</h2>
 * <p>「机器在不在工作」只用<b>本仓自己的</b>既有信号：{@code supplyStations(level)}（每 tick 一份的
 * 工位表，非网络扫描）+ {@code pendingStepOn(target)}（每 tick 每工位一份的容器 / 进度组件读取，
 * 已有备忘）+ 仓内 {@code hasInFlightUnit()}（本地槽位）。<b>没有任何网络查询</b>
 * （不调 {@code getPatterns*}、不遍历 {@code getResources}），因此每 tick 的调用次数是
 * <b>O(工位数)</b>（现场 1~4），与网络上资源数 / 样板数无关。</p>
 *
 * <h2>反例（自检里也钉住）</h2>
 * <ul>
 *     <li>「每 40 次引擎调用才评估一次」⇒ 最坏 200 tick 才开始反应，判负；</li>
 *     <li>「每个引擎节拍把目标表全量重扫一遍网络（{@code getPatterns} / 全网络容器枚举）」⇒ 判负；</li>
 *     <li>「不管有没有缺口都定时 ensureTask 一遍」⇒ 判负（请求必须仍是缺口驱动）。</li>
 * </ul>
 *
 * <p>纯常量 + 纯函数，无状态、无世界访问，故可被工具类自检直接取数值复算。</p>
 */
public final class MachineWorkSignal {
    /**
     * 热档的请求评估冷却（<b>引擎调用次数</b>，不是 tick）：0 = 每个引擎节拍都评估。
     * <p>「0」而不是「1」是有意的：调用点已经在引擎节流之后（每 5 tick 一次），再减 1 会白丢一个节拍。</p>
     */
    public static final int HOT_COOLDOWN_RUNS = 0;
    /**
     * 冷档的请求评估冷却（引擎调用次数）：40 次 × {@code ENGINE_INTERVAL_TICKS}=5 tick = 200 tick。
     * <p>与改动前逐字一致 —— 没有机器在工作时维持既有的低频自愈节拍，不引入额外的持续性开销。</p>
     */
    public static final int COLD_COOLDOWN_RUNS = 40;
    /**
     * 热档宽限（tick）：最后一次看见「工位 / 仓内确实压着在制件」之后，仍按热档处理的时长。
     * <p>40 tick（2 秒）覆盖「一件料被吃掉、下一帧才看到产出」的间隙；取值远大于一个引擎节拍
     * （5 tick），又远小于一次机器加工周期之外的等待，故不会把「真的停了」一直当热档。</p>
     */
    public static final int GRACE_TICKS = 40;
    /** 「机器正在工作」这条判据的成本口径（自检按它断言「没有全网络扫描」）。 */
    public static final String COST_MODEL = "O(工位数)：只用本仓工位表 / 每 tick 已备忘的待加工步 / 仓内槽位";

    private MachineWorkSignal() {
    }

    /**
     * 这一刻该按热档还是冷档处理。
     *
     * @param workingNow 本 tick 是否<b>真的</b>看见在制件（工位上 / 仓内）
     * @param now        当前世界 tick
     * @param graceUntil 上一次看见在制件时的「宽限截止 tick」（{@code now + GRACE_TICKS}）
     * @return {@code true} = 热档（每个引擎节拍评估请求）
     */
    public static boolean isHot(final boolean workingNow, final long now, final long graceUntil) {
        return workingNow || now <= graceUntil;
    }

    /** 该档位对应的请求评估冷却（引擎调用次数）。 */
    public static int cooldownRuns(final boolean hot) {
        return hot ? HOT_COOLDOWN_RUNS : COLD_COOLDOWN_RUNS;
    }
}
