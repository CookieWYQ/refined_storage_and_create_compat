package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 输出总线的一个「可导出类别」快照（服务端权威 → S2C 下发给输出总线界面）。
 *
 * <p><b>为什么不用位掩码</b>：类别集合由执行仓当前绑定的单元样板 + Create 配方数据推导，
 * 数量不固定（附属模组可以让一个配方出现 2 种以上的「输入性产物」，也可能带流体输入），写死位数挡不住。
 * 因此改用<b>稳定字符串 id</b> 标识每个类别，并整体以有序列表同步给客户端；
 * <b>类别数量不设上限</b>（列表可任意长，界面靠滚动翻页放得下）。</p>
 *
 * <p><b>id 规则（稳定、可持久化）</b>：</p>
 * <ul>
 *     <li>{@link #INTERMEDIATE_PREFIX} 或 {@code intermediate:<步序>}：中间产物类别（配方过渡件）。
 *     <b>带步序</b>（如 {@code intermediate:2}）表示「归属第 2 步（0-based）」，因此「第 N 步中间产物」
 *     是一个<b>独立可勾选的类别</b>；只有执行舱判不出步序时才退回 {@link #INTERMEDIATE}
 *     （不带步序的旧 id，与旧存档兼容）；</li>
 *     <li>{@value #INPUT_PREFIX} + <b>配方段</b> + {@code '#'} + 物品注册名：一种「输入性产物」→ 一个独立类别
 *     （例如 {@code input:create:track#minecraft:iron_nugget}）。配方段 = 该输入所属的序列装配配方 id，
 *     由 {@code SequenceExecutionChamberBlockEntity#inputCategoryIdForRecipe} 写入 —— 没有它，
 *     「精密构件」与「列车轨道」都声明的铁粒会并成同一个类别（见该类 id 方法的说明）；
 *     旧格式（{@code input:minecraft:iron_ingot}，无配方段）仍然认，按「配方未知」处理；</li>
 *     <li>{@value #FLUID_PREFIX} + 流体注册名：配方的一种「流体输入」→ 一个独立类别
 *     （例如 {@code fluid:minecraft:water}）；</li>
 *     <li>{@value #RESULT_PREFIX} + 物品注册名：配方的<b>成品</b>（= {@code results} 池里代表「主产物」的那一项）
 *     → 一个独立类别（例如 {@code result:create:precision_mechanism}）；
 *     <b>为什么必须有它</b>：成品原先不属于任何类别，界面上看不见、手动模式也选不到（用户验收标准 #3）；</li>
 *     <li>{@value #SCRAP_PREFIX} + 物品注册名：配方的<b>废料</b>（= {@code results} 池里除主产物外的概率产出）
 *     → 一个独立类别（例如 {@code scrap:create:iron_nugget}）。用户原话：「废料肯定要显示」。</li>
 * </ul>
 * <p>顺序由执行仓固定：物品输入 → 流体输入 → 中间产物（按步序升序）→ 成品 → 废料，
 * 同一组内按注册名升序（界面顺序稳定，便于「按组归类」显示）。</p>
 *
 * <p><b>共享语义</b>：同一类别可以被多台输出总线同时选中（{@code sharedCount > 1}），
 * 此时该类别的产出由执行仓按轮询在它们之间均分（见 {@code SequenceExecutionChamberBlockEntity}）。
 * <p>物品与流体走<b>同一套</b>归还逻辑，因此流体的水量同样按「输出总线的一次导出批次」轮询分配，
 * 由「执行舱内部存储的原子抽取 → 目标插入 → 余量回写执行舱」链路保证总量守恒、不丢不复制
 * （取货源是执行舱内部存储，见 {@link RsccChamberExportStrategy}）。</p>
 *
 * <p><b>本类刻意放在 support 包</b>：mixin 包被 mixins.json 声明为 Mixin 专用包，
 * 普通类放在其中被外部引用会抛 {@code IllegalClassLoadError}。</p>
 *
 * @param id          类别稳定 id（见上）
 * @param iconItem    物品类别的代表物品注册名（空串 = 非物品类别，见 {@code iconFluid}）
 * @param iconFluid   流体类别的代表流体注册名（空串 = 非流体类别）
 * @param labelKey    类别显示名的语言键；空串表示「用 {@code iconItem} / {@code iconFluid} 的真实名字」
 *                    （输入原料 / 流体输入 / 中间产物三类都走这条，玩家看到的始终是真实物品 / 流体名字，
 *                    不再把「输入性产物 / 中间产物」这类类别标签当名字）
 * @param stepMachine 「本步骤用的机器」物品注册名（<b>仅中间产物类别非空</b>）：
 *                    界面「详细配置」的<b>标签行</b>要用它把「第 N 步 · 冲压」写清楚
 *                    （用户硬要求：不要靠堆一排物品格来区分同一步序下的不同身份）。
 *                    取值口径 = 该步处理配方 {@code IAssemblyRecipe#addRequiredMachines} 的第一台机器，
 *                    服务端可安全取得（{@code getDescriptionForAssembly} 是 {@code @OnlyIn(CLIENT)}，用不了）；
 *                    空串 = 取不到 → 界面退回「第 N 步的中间产物」文案。
 * @param selected    本输出总线当前是否导出该类别
 * @param sharedCount 正在共享该类别的输出总线台数（含本机；&gt; 1 表示「均分」）
 * @param amount      该类别的「每批投入量」：物品 = 该原料的件数，流体 = 每批 mB（0 = 不显示数量）
 * @param estimated   「预估需求」：正常情况（不看概率）产出 <b>1 个最终产物</b>所需的量
 *                    （起始原料 = 每批需求量；步骤输入 = 每批需求量 × 配方 loops；中间产物 = 0）。
 *                    仅 «目标产物达标» 供应策略下界面显示，并提示「因概率原因实际可能超出」。
 * @param reuseKey    <b>「语义相同的中间步骤」复用键</b>（空串 = 不适用）：同一条产线里，
 *                    步骤类型 + 输入候选集合完全相同的两个步骤（可能来自<b>不同配方</b>，例如
 *                    列车轨道的冲压步与坚固板的冲压步、列车轨道的装铁粒步与精密构件的装铁粒步）
 *                    给出<b>同一个</b>键 —— 界面据此提示「这两步可复用同一台机器 / 同一条总线」，
 *                    而类别本身仍按配方分开（见 {@link #intermediateRecipe()}），两者不矛盾。
 */
public record RsccBusCategory(String id, String iconItem, String iconFluid, String labelKey,
                              String stepMachine, boolean selected, int sharedCount,
                              long amount, long estimated, String reuseKey,
                              /**
                               * <b>该类别的全部候选物品注册名</b>（{@code ,} 分隔；空串 = 单件）。
                               *
                               * <h2>为什么必须由服务端下发（2026-10-05 用户实测「铁粒和锌粒还是合并显示」）</h2>
                               * <p>界面原先在客户端<b>自己猜</b>候选：扫全部序列装配配方的 ingredient，
                               * 按「代表物」合并进一张 {@code Map<Item, List<ItemStack>>}。
                               * 而「精密构件」与「列车轨道」的机械手步<b>都声明了铁粒</b> ——
                               * 列车轨道那一组 {@code [铁粒, 锌粒]} 也挂到「铁粒」这个键上，
                               * 于是精密构件的铁粒格被显示成「铁粒或锌粒」。客户端根本不知道
                               * 「这一格属于哪条配方」，靠启发式永远猜不准。</p>
                               * <p>现在改由<b>服务端</b>在 {@code busCategorySnapshot} 里带上它自己算好的
                               * 候选（那里已经按配方限定类别，见 {@code inputCategoryIdForRecipe}），
                               * 客户端只负责显示 ⇒ 两条配方各显示各的，不再互相污染。</p>
                               */
                              String items) {
    /** 旧的 10 参构造器（保留，供既有代码编译）：候选缺省为空串（= 只有图标那一件）。 */
    public RsccBusCategory(final String id, final String iconItem, final String iconFluid,
                           final String labelKey, final String stepMachine, final boolean selected,
                           final int sharedCount, final long amount, final long estimated,
                           final String reuseKey) {
        this(id, iconItem, iconFluid, labelKey, stepMachine, selected, sharedCount, amount, estimated,
            reuseKey, "");
    }

    /** 该类别的候选物品注册名（{@code ,} 分隔；空串 = 只有图标那一件）。 */
    public java.util.List<String> candidateIds() {
        if (items == null || items.isEmpty()) {
            return java.util.List.of();
        }
        return java.util.List.of(items.split(","));
    }

    /** 旧的 9 参构造器（保留，供既有代码编译）：复用键缺省为空串。 */
    public RsccBusCategory(final String id, final String iconItem, final String iconFluid,
                           final String labelKey, final String stepMachine, final boolean selected,
                           final int sharedCount, final long amount, final long estimated) {
        this(id, iconItem, iconFluid, labelKey, stepMachine, selected, sharedCount, amount, estimated, "");
    }
    /** 「中间产物」类别的稳定 id 前缀；完整 id = 前缀（步序未知）或 前缀 + {@code ':'} + 步序。 */
    public static final String INTERMEDIATE_PREFIX = "intermediate";
    /**
     * 不带步序的「中间产物」id = {@link #INTERMEDIATE_PREFIX}。
     * <p><b>为什么保留</b>：① 执行舱判不出步序（样板带不出配方）时用它，界面按「步骤未知」展示；
     * ② 旧存档里玩家勾好的 {@code intermediate} 仍能被识别（执行舱会把旧 id 展开成各步 id）。</p>
     */
    public static final String INTERMEDIATE = INTERMEDIATE_PREFIX;
    /** 「输入性产物」类别 id 前缀；完整 id = 前缀 + 物品注册名。 */
    public static final String INPUT_PREFIX = "input:";
    /** 「流体输入」类别 id 前缀；完整 id = 前缀 + 流体注册名。 */
    public static final String FLUID_PREFIX = "fluid:";
    /**
     * 「成品」类别 id 前缀；完整 id = 前缀 + 物品注册名。
     * <p><b>为什么要有它（用户验收标准 #3）</b>：类别模型原先只有 {@code input:} / {@code fluid:} /
     * {@code intermediate:} 三类，<b>成品与废料不属于任何类别</b> —— 界面上看不见、手动模式选不到。
     * 本轮让执行仓从所属序列装配配方的 {@code results} 池派生成品与废料类别，
     * 两者都「可显示、可勾选」；自动模式行为不变（非输入类恒收回）。</p>
     */
    public static final String RESULT_PREFIX = "result:";
    /**
     * 「废料」类别 id 前缀；完整 id = 前缀 + 物品注册名。
     * <p>废料 = {@code create:sequenced_assembly} 配方 {@code results} 池里除「主产物」以外的概率产出
     * （拆分口径与 SPT / JEI 完全一致，见 {@code SequencedRecipeProbe#splitResultPool}）。
     * 用户原话：「废料肯定要显示」。</p>
     */
    public static final String SCRAP_PREFIX = "scrap:";
    /**
     * <b>「配方标签页标记」类别的 id 前缀</b>；完整 id = 前缀 + <b>处理器类型</b>（{@code recipeType}）id
     * （例如 {@code recipe:create:pressing}）。
     *
     * <h2>为什么要有它</h2>
     * <p>执行舱整条链<b>一台单元样板都没有</b>时，类别表为空（第 35 轮：不猜「具体配方 + 步序」）。
     * 但「这台机器能做这类加工」是<b>已知事实</b>（= 本仓绑定的 {@code recipeType}），
     * 因此总线的「类别详细配置」界面<b>仍要建配方标签页</b>，只是<b>页内一条都不显示</b> ——
     * 没有单元样板就没有「具体配方 + 步序」，也就没有任何可交接的原料 / 中间产物。</p>
     * <p><b>它携带的是「能干哪一类加工」，不是「哪一条配方」</b>：具体配方由<b>客户端</b>用它去查
     * 配方管理器（「所有含该处理器类型步骤的序列装配配方」，见
     * {@code BusCategoryConfigScreen#recipeTabKeysOf}）展开成<b>一配方一页</b>，
     * 页名走既有的配方名解析（结果物悬浮名）。上一版直接拿它当页名、走配方类型译名，
     * 于是 {@code create:deploying} 显示成 Create 自己的「使用」—— 用户否掉了那个动词页。</p>
     *
     * <h2>它不是「类别」：不画行、不可勾选、不进任何判定</h2>
     * <ul>
     *     <li><b>不画行</b>：界面在所有会产出行 / 计数 / 搜索命中的枚举处先跳过它
     *     （分节成员、页成员、跨页命中），因此列表一行都不多、分节计数与「已选 N / M 项」
     *     也不把它算进去；它唯一被读的地方是「这个类别属于哪些页」（展开成配方页）；</li>
     *     <li><b>不可勾选</b>：服务端一律以 {@code selected = false} 下发，客户端也不把它放进勾选集合
     *     ⇒ 提交的勾选表里永远不会出现它的 id（因此它永远进不了总线的落盘配置）；</li>
     *     <li><b>不进服务端任何判定</b>：它只出现在两个界面快照
     *     （{@code SequenceExecutionChamberBlockEntity#busCategorySnapshot} /
     *     {@code #busImportCategorySnapshot}）的<b>返回值</b>里，
     *     <b>不</b>进 {@code busCategories()} 类别表缓存、{@code chainCategories()}、
     *     {@code busCategoryOwners} 归属表、备料 / 供料目标、排队 / 名额 / 在制计数 ——
     *     那些判定的数据源一个字都没变（证明见 {@code tools/selfcheck_round39_*} 与
     *     {@code tools/selfcheck_round41_*}）。</li>
     * </ul>
     */
    public static final String RECIPE_TAB_PREFIX = "recipe:";

    /**
     * 手写编解码（而不是 {@code StreamCodec.composite}）：本记录字段已超过 {@code composite}
     * 最多接受的 6 组「codec + getter」，因此这里逐字段写，
     * 字段顺序 = 记录构造参数顺序，两端一致。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, RsccBusCategory> STREAM_CODEC =
        StreamCodec.of(RsccBusCategory::encode, RsccBusCategory::decode);

    private static void encode(final RegistryFriendlyByteBuf buf, final RsccBusCategory value) {
        ByteBufCodecs.STRING_UTF8.encode(buf, value.id());
        ByteBufCodecs.STRING_UTF8.encode(buf, value.iconItem());
        ByteBufCodecs.STRING_UTF8.encode(buf, value.iconFluid());
        ByteBufCodecs.STRING_UTF8.encode(buf, value.labelKey());
        ByteBufCodecs.STRING_UTF8.encode(buf, value.stepMachine());
        ByteBufCodecs.BOOL.encode(buf, value.selected());
        ByteBufCodecs.VAR_INT.encode(buf, value.sharedCount());
        ByteBufCodecs.VAR_LONG.encode(buf, value.amount());
        ByteBufCodecs.VAR_LONG.encode(buf, value.estimated());
        ByteBufCodecs.STRING_UTF8.encode(buf, value.reuseKey() == null ? "" : value.reuseKey());
        ByteBufCodecs.STRING_UTF8.encode(buf, value.items() == null ? "" : value.items());
    }

    private static RsccBusCategory decode(final RegistryFriendlyByteBuf buf) {
        return new RsccBusCategory(
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_LONG.decode(buf),
            ByteBufCodecs.VAR_LONG.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf)
        );
    }

    /** 是否是「输入性产物」类别（物品输入或流体输入，两者都属「输入原料」）。 */
    public boolean isInput() {
        return id != null && (id.startsWith(INPUT_PREFIX) || id.startsWith(FLUID_PREFIX));
    }

    /** 是否是「流体输入」类别。 */
    public boolean isFluidInput() {
        return id != null && id.startsWith(FLUID_PREFIX);
    }

    /**
     * 是否是「中间产物」类别（含带步序的 {@code intermediate:<step>} 与不带步序的 {@link #INTERMEDIATE}）。
     * <p><b>为什么用前缀判定而不是 equals</b>：本轮把中间产物按「归属步骤」拆成了多个独立类别，
     * 而 {@link #INTERMEDIATE} 只是「步序未知」那一种写法；用 equals 会把带步序的类别漏掉。</p>
     */
    public boolean isIntermediate() {
        return id != null && (id.equals(INTERMEDIATE_PREFIX)
            || id.startsWith(INTERMEDIATE_PREFIX + ":"));
    }

    /**
     * 中间产物类别的「归属步骤」（0-based）；非中间产物类别或步序未知时返回 {@code -1}。
     *
     * <p><b>解析规则（本轮改为「取最后一段」）</b>：类别 id 现在可能带<b>配方</b>
     * （{@code intermediate:create:track:2}，见 {@link #intermediateId(String, int)}），
     * 而配方 id 自身含一个冒号，因此步序只能从<b>最后</b>一个冒号之后解析；
     * 旧的 {@code intermediate:2}（不带配方）同样落在最后一段上，两种写法共用本解析。</p>
     */
    public int intermediateStep() {
        if (!isIntermediate()) {
            return -1;
        }
        final int last = id.lastIndexOf(':');
        if (last < 0 || last + 1 >= id.length()) {
            return -1;
        }
        try {
            return Integer.parseInt(id.substring(last + 1));
        } catch (NumberFormatException exception) {
            return -1; // 非法 id：按「步序未知」处理（绝不抛，界面照常显示）
        }
    }

    /**
     * 中间产物类别的「归属配方 id」（{@code create:track}）；不带配方（旧 id / 步序未知）时返回空串。
     *
     * <h2>为什么中间产物必须按配方区分（用户第 3 条）</h2>
     * <p>用户原话：「要按不同的物品的配方来区分……难道说中间产物就要有坚固板的，又要有列车轨道的吗？」
     * 旧 id 只有步序（{@code intermediate:2}），于是「坚固板的第 3 步（冲压）」与
     * 「列车轨道的第 3 步（冲压）」落进<b>同一个</b>类别：图标与「按步过滤原型」只有一份
     * （先注册的那条配方赢了），另一条配方的过渡件因此<b>永远导不出去</b> ——
     * 用户实测「那个冲压的地方……输出的地方压根啥也没检测到」。现在类别按「配方 + 步序」分开，
     * 两条配方的同一步序各有独立类别 / 图标 / 过滤原型，界面上的分组也自然分开。</p>
     */
    public String intermediateRecipe() {
        if (!isIntermediate() || intermediateStep() < 0) {
            return "";
        }
        final int last = id.lastIndexOf(':');
        final int from = INTERMEDIATE_PREFIX.length() + 1; // 跳过前缀与它后面那一个冒号
        if (last <= from) {
            return ""; // 旧写法 intermediate:<步序>：没有配方段（绝不 substring(begin > end)，会抛）
        }
        return id.substring(from, last);
    }

    /**
     * 按「归属步骤」拼中间产物类别的 id（<b>不带配方</b>的旧写法，保留给「步序未知 / 配方未知」）。
     *
     * @param step 步骤下标（0-based）；{@code < 0} = 步序未知 → {@link #INTERMEDIATE}
     */
    public static String intermediateId(final int step) {
        return step < 0 ? INTERMEDIATE : INTERMEDIATE_PREFIX + ":" + step;
    }

    /**
     * 按「<b>归属配方 + 步骤</b>」拼中间产物类别的 id：{@code intermediate:<配方id>:<步序>}
     * （例如 {@code intermediate:create:track:2}）。
     *
     * <p><b>唯一权威写法</b>：只要拿得到配方 id 就用本方法 —— 这样「不同配方的同一步序」
     * 是两个互不覆盖的类别（各自的图标 / 按步过滤原型 / 勾选状态互不影响），
     * 而「同一条配方的相邻同类型步骤」（坚固板第 2、3 步都是冲压）仍是两个独立类别
     * （用户要求「第 N 步」可分别勾选）。配方未知时退回不带配方的 {@link #intermediateId(int)}。</p>
     *
     * @param recipeId 所属序列装配配方 id（可空 / 空串 = 未知）
     * @param step     步骤下标（0-based）；{@code < 0} = 步序未知 → {@link #INTERMEDIATE}
     */
    public static String intermediateId(@org.jetbrains.annotations.Nullable final String recipeId,
                                        final int step) {
        if (step < 0) {
            return INTERMEDIATE;
        }
        if (recipeId == null || recipeId.isEmpty()) {
            return intermediateId(step);
        }
        return INTERMEDIATE_PREFIX + ":" + recipeId + ":" + step;
    }

    /**
     * <b>静态版</b>旧 id 判定（手上只有 id 字符串时用）。
     *
     * <p>旧写法 = {@link #INTERMEDIATE}（步序未知）或 {@code intermediate:<步序>}（<b>只带一段</b>）；
     * 新写法 {@code intermediate:<配方id>:<步序>} 含两个及以上冒号，因此不会被误判。</p>
     */
    public static boolean isLegacyIntermediateId(@org.jetbrains.annotations.Nullable final String id) {
        if (id == null) {
            return false;
        }
        if (INTERMEDIATE.equals(id)) {
            return true;
        }
        if (!id.startsWith(INTERMEDIATE_PREFIX + ":")) {
            return false;
        }
        final String body = id.substring(INTERMEDIATE_PREFIX.length() + 1);
        return body.indexOf(':') < 0; // 只有一段（步序）= 旧写法
    }

    /** 旧 id 的步序（{@code intermediate} = -1 表示「全部步骤」）。 */
    public static int legacyIntermediateStep(final String id) {
        if (id == null || !isLegacyIntermediateId(id) || INTERMEDIATE.equals(id)) {
            return -1;
        }
        try {
            return Integer.parseInt(id.substring(INTERMEDIATE_PREFIX.length() + 1));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    /**
     * 是否是「成品」类别（配方 {@code results} 池的主产物）。
     * <p>与 {@link #isInput()} 互斥：成品不是输入原料，因此在自动收回模式下照旧被收回（行为不变）。</p>
     */
    public boolean isResult() {
        return id != null && id.startsWith(RESULT_PREFIX);
    }

    /** 是否是「废料」类别（配方 {@code results} 池里除主产物以外的概率产出）。 */
    public boolean isScrap() {
        return id != null && id.startsWith(SCRAP_PREFIX);
    }

    /**
     * 是否是「产出侧」类别（成品或废料）。
     * <p><b>为什么要这一层</b>：界面按组归类（输入 / 流体 / 中间产物 / 成品 / 废料）与「非输入类恒收回」
     * 都只需要「是不是产出侧」这一个判断，两处共用同一实现，不会出现两套口径。</p>
     */
    public boolean isProduct() {
        return isResult() || isScrap();
    }

    /**
     * 是否是「产出侧」类别的 id（成品 {@code result:} 或废料 {@code scrap:}）。
     * <p><b>为什么要一个静态版</b>：输出总线在<b>收到 / 读档</b>时就要把产出侧 id 丢掉
     * （服务端权威：输出总线不得提供成品，用户原话「输出总线包含一个可以选择成品的是什么鬼」），
     * 那时手上只有 {@code String}，没有记录对象；这里与 {@link #isProduct()} 共用同一批前缀常量，
     * 不会出现两套口径。</p>
     */
    public static boolean isProductId(final String id) {
        return id != null && (id.startsWith(RESULT_PREFIX) || id.startsWith(SCRAP_PREFIX));
    }

    /** 该「产出侧」类别对应的物品注册名（非成品 / 废料类别返回空串）。 */
    public String productItemId() {
        if (isResult()) {
            return id.substring(RESULT_PREFIX.length());
        }
        if (isScrap()) {
            return id.substring(SCRAP_PREFIX.length());
        }
        return "";
    }

    /**
     * 按<b>处理器类型</b>拼「只喂标签页」的标记类别 id（见 {@link #RECIPE_TAB_PREFIX}）。
     *
     * <p><b>为什么这里给的是处理器类型而不是配方 id</b>：这是执行舱（服务端）唯一确定的事实 ——
     * 「这台机器能干 {@code create:pressing} 这类加工」。具体是哪些序列装配配方用到它，
     * 由<b>客户端</b>读自己那份配方管理器展开成「一配方一页」
     * （{@code BusCategoryConfigScreen#recipeTabKeysOf}，解析器与服务端判定步骤类型时同一个），
     * 因此服务端一个协议字节都不用加，也不需要在这里猜配方。</p>
     *
     * @param recipeType 执行舱绑定的处理器类型 id（{@code create:pressing}）；
     *                   空 / {@code null} ⇒ 返回空串（= 连「能干什么」都不知道，不建页）
     */
    public static String recipeTabId(@org.jetbrains.annotations.Nullable final String recipeType) {
        return recipeType == null || recipeType.isEmpty() ? "" : RECIPE_TAB_PREFIX + recipeType;
    }

    /**
     * 是否是「只喂标签页」的标记类别（{@link #RECIPE_TAB_PREFIX} + 非空的一段）。
     * <p>界面据此把它从所有行 / 计数 / 命中里排除；判定与 {@link #recipeTabType()} 共用同一个前缀常量，
     * 不会出现两套口径。</p>
     */
    public boolean isRecipeTab() {
        return id != null && id.length() > RECIPE_TAB_PREFIX.length()
            && id.startsWith(RECIPE_TAB_PREFIX);
    }

    /**
     * 标记类别携带的那一段（非标记类别 ⇒ 空串）。
     * <p>服务端当前发的是<b>处理器类型 id</b>（{@code create:pressing}）；界面两种写法都认，
     * 若这段本身就是一条序列装配配方 id（{@code create:track}）则直接当作那一页
     * （见 {@code BusCategoryConfigScreen#recipeTabKeysOf}）。</p>
     */
    public String recipeTabType() {
        return isRecipeTab() ? id.substring(RECIPE_TAB_PREFIX.length()) : "";
    }

    /**
     * 该「输入性产物」类别对应的物品注册名（非物品输入类别返回空串）。
     *
     * <h2>为什么这里必须剥掉配方段（2026-10-06「原料 / 输入时原料 混在一起」的根因）</h2>
     * <p>类别 id 自 {@code inputCategoryIdForRecipe} 起已变成 {@code input:<配方id>#<物品>}，
     * 而旧实现直接返回前缀之后的<b>一整段</b>：给出去的是 {@code create:track#minecraft:iron_nugget}
     * —— 它既不是合法的 {@link net.minecraft.resources.ResourceLocation}（{@code #} 不是路径合法字符，
     * 解析直接失败），也不是任何物品的注册名。界面据此判分组时只能拿到 {@code null}，于是
     * <b>每一个物品输入都退化成「原料」，「输入时原料」永远为空</b> —— 这正是玩家看到的
     * 「两个分类混在一起、没有区分」。现在只返回物品段，配方段由 {@link #inputRecipeId()} 单独提供。</p>
     */
    public String inputItemId() {
        if (isFluidInput() || !isInput()) {
            return "";
        }
        final String body = id.substring(INPUT_PREFIX.length());
        final int hash = body.indexOf('#');
        return hash < 0 ? body : body.substring(hash + 1);
    }

    /**
     * 该「输入性产物」类别所属的序列装配配方 id（{@code input:<配方id>#<物品>} 里的配方段）；
     * 旧格式 id（{@code input:<物品>}，没有配方段）与流体类别返回空串。
     *
     * <p>「这一格属于哪条配方」是界面把物品输入分成「原料（该配方的起步原料）」与
     * 「输入时原料（该配方各步的投入物）」的<b>唯一上下文来源</b>：只按全局物品集合判，
     * 同一件东西（金板 / 铁粒）既可能是一条配方的起步原料、又是另一条配方的步内投入物，
     * 两个分组必然互相错怪。</p>
     */
    public String inputRecipeId() {
        if (isFluidInput() || !isInput()) {
            return "";
        }
        final String body = id.substring(INPUT_PREFIX.length());
        final int hash = body.indexOf('#');
        return hash <= 0 ? "" : body.substring(0, hash);
    }

    /** 该「流体输入」类别对应的流体注册名（非流体输入类别返回空串）。 */
    public String fluidInputId() {
        return isFluidInput() ? id.substring(FLUID_PREFIX.length()) : "";
    }

    /** 该类别是否正被多台输出总线共享（共享时按轮询均分）。 */
    public boolean isShared() {
        return sharedCount > 1;
    }
}
