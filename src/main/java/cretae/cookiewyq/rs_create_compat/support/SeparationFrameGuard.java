package cretae.cookiewyq.rs_create_compat.support;

import com.simibubi.create.content.fluids.pipes.AxisPipeBlock;
import com.simibubi.create.content.fluids.pipes.EncasedPipeBlock;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlock;
import com.simibubi.create.content.fluids.pipes.SmartFluidPipeBlock;
import com.simibubi.create.content.kinetics.simpleRelays.ShaftBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.ticks.TickPriority;
import org.jetbrains.annotations.Nullable;

/**
 * 「分隔框架套壳」的<b>唯一连接判定入口</b>：RS 网络图连边、RS 连接臂外形、
 * 本模组的线缆搜索（{@link RsccWireLinkSearch}）以及 Create 流体管道的接线判定，
 * 全都只问这一句话。
 *
 * <p><b>为什么必须收敛到一处</b>：一条「连接」在任何模组里都有多个消费者 —— RS 里是
 * 「网络图连边」（{@code ConnectionProviderImpl#getConnections}）与「连接臂外形」
 * （{@code AbstractCableLikeBlockEntity#hasVisualConnection}），Create 里是
 * 「管口开放面 blockstate」、两侧网络遍历与外形（都读 {@code FluidPipeBlock#canConnectTo}）。
 * 只要判定散在多处，一旦漂移就会出现「看着连着、实际没连」的鬼影。
 * 因此这里只保留<b>一个语义核心</b> {@link #blockedBySnapshot}，上面挂两个薄适配器：
 * {@link #blocksConnection}（RS）与 {@link #blocksFluidPipeConnection}（Create 流体管道）。</p>
 *
 * <h2>判定语义 = 「冻结套上那一刻的连接状态」（本轮纠正，取代旧的「断开一切」）</h2>
 * <p>用户原话：「他是说<b>保存当前连接状态不会发生改变</b>……它是<b>保存固定被套上之前最后一刻的状态</b>，
 * 而不是让它直接断开。」于是判定写成：</p>
 * <ol>
 *     <li><b>只对「管道类/线缆类 ↔ 管道类/线缆类」生效</b>（RS：{@link RsccWireBlocks#isWire}，
 *     即线缆 / 输入总线 / 输出总线；Create：{@link FluidPipeBlock#isPipe} 流体管道）。
 *     管道与机器、容器之间的连接<b>不受影响</b>；</li>
 *     <li><b>未套壳的格完全照旧</b>（走 RS / Create 自己的自然规则，这里不介入）；</li>
 *     <li><b>已套壳的格 P 与邻居 N</b>：连接<b>当且仅当</b>「{@code N} 在 {@code P} 的快照集合里」
 *     —— 套上前就有的连接照旧连（一条直的套上后仍然彼此互通），套上后新放的邻居一律不连；</li>
 *     <li><b>对称性</b>：判定 N 侧时若邻居 P 已套壳，也必须以 <b>P 的快照</b>为准
 *     （见 {@link #blockedBySnapshot}：先看自己有没有快照，没有就看对面那一格的快照，
 *     对面也没有才放行）。两端只要有一端是套壳格，就由套壳格说了算，因此不会出现「单边连上」。
 *     两侧<b>同时</b>被套住时两个快照必然一致：后套上那一格的快照就是用本类这套判定算出来的
 *     （见 {@link #snapshotMask}），前者的快照在哪一侧置位，后者就跟着置位。</li>
 * </ol>
 *
 * <p><b>服务端 / 客户端一致性</b>：记录（含快照）由 {@link RsccSheaths} 保存并随 S2C 快照同步，
 * 两侧读同一份数据（服务端读 {@code SavedData}，客户端读只读镜像），
 * 因此判定天然一致；不存在「服务端存了、客户端还没收到」的窗口（快照先于方块更新下发）。</p>
 *
 * <p><b>即时生效</b>：套上 / 取下后由 {@link #refreshAround} 主动把那一格与六个邻块的
 * RS 连接与网络图各重算一次，并给邻接的 Create 管道排一次 HIGH 优先级 tick
 * （Create 自己在「管道放置 / 邻块变化」时用的同一条路），保证一个 tick 内生效。</p>
 *
 * <p><b>没有缓存</b>：本类不缓存任何判定结果，所以不存在「需要失效却漏掉」的情况；
 * 唯一的代价是每次判定多读十来个方块状态（只有接缝两侧确实是管道/线缆时才会走到）。</p>
 */
public final class SeparationFrameGuard {
    private SeparationFrameGuard() {
    }

    /**
     * 该方块状态是否属于 <b>Create 流体管道族</b>（→ 可套壳，且套壳后它的接缝由快照说话）。
     *
     * <h2>为什么是「类别判定」而不是方块清单（用户要求的修正）</h2>
     * <p>用户原话：「不是只能放在线缆和流体管道上面，要兼容<b>所有</b>这种线缆类和流体管道类的方块」。
     * 原来的判定只有 {@code FluidPipeBlock.isPipe} 一条（= 只认最普通的流体管道），于是
     * 「玻璃流体管道 / 装壳后的管道 / 智能流体管道」这些同族方块全都不认 —— 那正是用户抱怨的
     * 「只能放在某几种上面」。这里改成按 <b>Create 自己的类层次</b>判定（一个类家族，而不是一串方块 id）：</p>
     * <ul>
     *     <li>{@link FluidPipeBlock}（含它的<b>全部子类</b>，包括其它模组继承它新增的管道）——
     *     判定直接复用 Create 自己的 {@link FluidPipeBlock#isPipe(BlockState)}，不另立口径；</li>
     *     <li>{@link EncasedPipeBlock}：「装壳后的管道」。Create 把它写成了独立类（不继承
     *     {@code FluidPipeBlock}），但它是<b>同一个方块实体</b>（{@code FluidPipeBlockEntity}）、
     *     同一套六向管口属性，语义上就是管道本身；</li>
     *     <li>{@link AxisPipeBlock}：直管族（{@code GlassFluidPipeBlock} 玻璃流体管道），
     *     只有轴向属性、没有六向管口属性；</li>
     *     <li>{@link SmartFluidPipeBlock}：智能流体管道（面朝两向的管口）。</li>
     * </ul>
     * <p><b>刻意排除</b>：{@code PumpBlock}（动力泵）、{@code FluidValveBlock}（流体阀）、
     * {@code HosePulleyBlock}（软管卷筒）虽然与管道接线，但它们是<b>机器 / 机构</b>
     * （有自己的应力与交互语义），套壳对它们没有意义，也和「套在管道上」这件事不是一类。</p>
     * <p><b>可扩展性</b>：Create / 其它模组将来新增同族方块时，只要它继承上述任一类即自动被覆盖；
     * 若新增的是「另一个独立类但同族」，只需在这里补一条 {@code instanceof}（类别判定），
     * <b>不需要</b>去改任何方块 id 清单。</p>
     */
    public static boolean isFluidPipe(@Nullable final BlockState state) {
        if (state == null) {
            return false;
        }
        final Block block = state.getBlock();
        return FluidPipeBlock.isPipe(state) || block instanceof EncasedPipeBlock
            || block instanceof AxisPipeBlock || block instanceof SmartFluidPipeBlock;
    }

    /**
     * 该方块状态是不是「可以被套壳的管道 / 线缆」（RS 线缆族 + Create 流体管道族）。
     *
     * <p><b>用途（强制约束）</b>：手持分隔框架 / 伪装框架时，只有点在它上面才允许生效；
     * 点在别的方块上<b>不得放置、不得生效</b>，只给一句 actionbar 提示。</p>
     *
     * <p><b>两个框架共用这一句话</b>（分隔框架的物品路径 = {@code item/SeparationFrameItem}，
     * 伪装框架的物品路径 = {@code item/CamouflageFrameItem}），因此「能用在哪」永远只有一份口径。</p>
     *
     * <p><b>两族都是类别判定</b>：线缆族见 {@link RsccWireBlocks#isWire}（按 RS 的方块类判定，
     * 16 种颜色的线缆共用一个 {@code CableBlock} 类），管道族见 {@link #isFluidPipe}。</p>
     *
     * <h2>2026-09-25 补：这是<b>不带世界</b>的廉价版，只认管道 / 线缆族</h2>
     * <p>用户第 5 条要求放宽到「任意完整方块」（例：「我想放这个单元样板管理舱」），
     * 但「是不是整格完整方块」这件事必须读方块状态自带的那份碰撞形状
     * （{@code BlockState#isCollisionShapeFullBlock}；非动态外形方块读的是烘焙缓存，见重载注释），
     * 因此放宽后的完整判定在带世界的重载 {@link #isSheathable(BlockGetter, BlockPos, BlockState)} 里，
     * 所有交互 / 渲染 / 连锁的准入一律走那一个重载；本方法只保留「最便宜的那一半」，
     * 供不需要查世界的场合（例如已经在别处确认过方块族的渲染快速筛）使用。</p>
     */
    public static boolean isSheathable(@Nullable final BlockState state) {
        return isSheatheableFamily(state);
    }

    /**
     * <b>廉价版「是不是可套的管道 / 线缆族」</b>（= {@link #isSheathable(BlockState)} 的语义）：
     * 只看 {@code state.getBlock()} 的类层次，<b>不查世界、不查形状、不查套壳记录</b>。
     *
     * <h2>2026-09-26 崩溃修复：形状 hook 里只允许这一句话</h2>
     * <p>形状查询路径（{@code mixin/block/CamouflageShapeMixin} → {@code RsccCamouflage.overridesShape}）
     * 会被「方块状态烘焙 / 实体碰撞 / 选中框 / 遮挡剔除」等<strong>每一处</strong>形状查询击中，
     * 它<strong>绝不能</strong>再触发任何形状查询 —— 否则就会重演 2026-09-26 的
     * {@code Bootstrap} 无限递归（详见 {@code CamouflageShapeMixin} 的类注释）。</p>
     * <p>而「任意完整方块也能套壳」（用户第 5 条）这件事<strong>只有在形状路径之外</strong>才判得安全
     * ——它必须读方块状态自带的那份碰撞形状。因此本方法被单独抽出来，作为形状路径可用的唯一判据。</p>
     */
    public static boolean isSheatheableFamily(@Nullable final BlockState state) {
        return RsccWireBlocks.isWire(state) || isFluidPipe(state) || isShaft(state);
    }

    /**
     * 该方块状态是不是 <b>Create 的传动杆族</b>（本轮新增：允许伪装框架套在传动杆上）。
     *
     * <h2>为什么是 {@link ShaftBlock} 这一个类</h2>
     * <p>用户第 2 条要求「允许伪装框架套在<b>传动杆</b>上」。Create 的传动杆是
     * {@code com.simibubi.create.content.kinetics.simpleRelays.ShaftBlock}（注册项 {@code create:shaft}），
     * 它继承 {@code AbstractSimpleShaftBlock}：<b>只有轴向属性、没有管口</b>，
     * 形状是一根 12/16 的圆柱（不是整格），因此「套上壳之后按整格参与碰撞 / 选中」
     * 对它同样有意义 —— 与线缆 / 管道族完全同一类需求。</p>
     *
     * <h2>为什么把「传动杆」补进这一族，而不是给伪装框架单开一张表</h2>
     * <p>两个框架（分隔 / 伪装）的可套判定共用 {@link #isSheatheableFamily} 这一句话
     * （见 {@link #isSheathable(BlockGetter, BlockPos, BlockState)} 的注释：「能用在哪」只有一份口径）。
     * 传动杆属于「细芯 + 连接臂」这一族外形，把它补进这一族即可；
     * 若改成「只有伪装框架能套传动杆、分隔框架不能」，就等于把一句话拆成两句，
     * 将来必然漂移 —— 因此<b>保持单一口径</b>。</p>
     *
     * <p><b>刻意不改变任何连接语义</b>：本方法只回答「能不能套壳」；
     * 传动杆既不是 RS 线缆（{@link RsccWireBlocks#isWire}）也不是流体管道（{@link #isFluidPipe}），
     * 因此 {@link #blocksConnection} / {@link #blocksFluidPipeConnection}（分隔框架的「冻结连接快照」）
     * 对传动杆一律不介入 —— 传动杆的传动连接、应力网络分毫不动。</p>
     *
     * <p><b>可扩展</b>：Create / 其它模组将来新增同族的简单传动件（例如另一个继承
     * {@code AbstractSimpleShaftBlock} 的类）时，若也要支持，只需在这里补一条 {@code instanceof}
     * （沿用「按类判定、不维护方块 id 清单」的既有口径）。</p>
     */
    public static boolean isShaft(@Nullable final BlockState state) {
        return state != null && state.getBlock() instanceof ShaftBlock;
    }

    /**
     * 可套壳判定（<b>本轮改成「默认只认管道 / 线缆族」＋ 可选放宽</b>）：
     * 管道 / 线缆族 <b>∪（配置开启时）任意完整方块</b>。
     *
     * <h2>为什么放宽必须是一个「默认关闭的可选功能」</h2>
     * <p>用户原话：「把它作为一个<b>可选功能</b>，<b>默认是关闭的</b>，在<b>配置里可以选择打开</b>。」
     * 于是判定拆成两段：<b>第一段永远是管道 / 线缆族</b>（真正的使用场景），
     * <b>第二段（任意完整方块）由 {@code Config#frameArbitraryBlocks} 把关，默认 false</b> ——
     * 默认关时这一段在任何形状查询之前就返回 false，因此默认配置下既没有额外开销，
     * 也不会出现「随便点个方块就被裹住」的意外；关闭时对不支持的方块由调用方给出<b>简短反馈</b>
     * （{@code item/SeparationFrameItem} 与 {@code item/CamouflageFrameItem} 都只提示、不生效、不消费）。</p>
     *
     * <h2>开启后为什么不会破坏既有交互</h2>
     * <p>用户原话：「为什么有些方块不能直接放上去？比如说我想放这个单元样板管理舱」。
     * 套壳<b>从头到尾不替换、不修改方块本身</b>（只写坐标记录 + 在外面画一层壳，见 {@link RsccSheaths}
     * 与 {@link RsccCamouflage} 的类注释），被裹方块的方块状态、方块实体、能力、网络节点一个字节都没变，
     * 因此「完整的普通方块 / 机器 / 容器」套壳既不会丢内容，也不会改它的交互（右键照旧是它自己的交互）。</p>
     * <p>开启后排除项只有四类，每一类都有明确理由：</p>
     * <ol>
     *     <li><b>空气</b>：没有东西可以被裹；</li>
     *     <li><b>流体方块</b>（{@code getFluidState} 非空）：它没有稳定的立方体外形，裹上去只是视觉噪音；</li>
     *     <li><b>可替换方块</b>（{@code canBeReplaced}，草 / 雪 / 火把一类）：玩家放东西时会被原版替换掉，
     *     壳会留在空气上 —— 而清理钩子（方块变化）随即会把记录清掉，等于白做还费一个物品；</li>
     *     <li><b>非整格外形</b>：半砖 / 栅栏 / 台阶 / 火把一类，外壳（整格立方体）比它大得多，
     *     看起来就是「凭空多出一个方块」，与「裹住它」的语义不符。</li>
     * </ol>
     * <p><b>已知边界（明确写下来）</b>：给一个带界面的机器（例如单元样板管理舱）套壳后，
     * 那一格<b>看得见的是外壳</b>（这正是套壳的目的），但机器的交互完全不受影响
     * （不取消、不修改任何右键行为，也不加碰撞），潜行 + 扳手 / 潜行右键随时可以取回外壳。</p>
     *
     * <h2>2026-09-26 崩溃修复：「整格完整方块」判定改成不触发形状计算的读法</h2>
     * <p>旧实现在这里直接写 {@code state.isCollisionShapeFullBlock(level, pos)}，而它<strong>曾经</strong>
     * 被形状 hook（{@code RsccCamouflage.overridesShape}）调用 → 见 {@code CamouflageShapeMixin} 的类注释：
     * 在 {@code cache == null} 的方块状态烘焙期，这一句会转交
     * {@code Block.isShapeFullBlock(state.getCollisionShape(level, pos))}，
     * 也就是<strong>再触发一次形状查询</strong>，与形状 hook 构成闭环 → {@code Bootstrap} 栈溢出。</p>
     * <p>现在的两条约束一起把环彻底拆掉：</p>
     * <ol>
     *     <li><b>形状路径不再走到这里</b>：{@link #isSheatheableFamily} 才是形状路径唯一判据
     *     （见其注释），{@link RsccCamouflage#overridesShape} 已不再调用本方法；</li>
     *     <li><b>本方法自己也不再触发形状计算</b>：先 {@code hasDynamicShape()} 早退
     *     —— 动态外形方块的碰撞形状本来就<b>不是</b>「一个稳定的整格」（线缆 / 管道 / 依赖位置或
     *     邻居的方块全在此列），直接判「非整格」；
     *     剩下的都是<b>非动态外形</b>方块，原版
     *     {@code BlockBehaviour.BlockStateBase#isCollisionShapeFullBlock} 对它们的实现是
     *     {@code this.cache != null ? this.cache.isCollisionShapeFullBlock : ...}
     *     ——即<strong>只读一份烘焙期算好的布尔缓存</strong>，不会调用
     *     {@code getCollisionShape} / {@code getShape}，<b>不会回到形状 hook</b>。
     *     （对非动态外形方块而言这份缓存与位置无关，因此传世界 / 坐标只是签名所需，不参与计算。）</li>
     * </ol>
     *
     * @param level 用于判定「整格完整方块」的世界（{@code null} = 没有世界可用 → 只认管道 / 线缆族）
     * @param pos   该方块的坐标（签名所需；非动态外形方块的整格判定与位置无关）
     */
    public static boolean isSheathable(@Nullable final BlockGetter level, @Nullable final BlockPos pos,
                                       @Nullable final BlockState state) {
        if (isSheatheableFamily(state)) {
            return true; // 管道 / 线缆族：永远可套（它们本来就不是完整方块）
        }
        if (!cretae.cookiewyq.rs_create_compat.Config.frameArbitraryBlocks) {
            // 默认只认管道 / 线缆族：放宽到「任意完整方块」是可选功能（配置项 frameArbitraryBlocks，默认关）。
            // 放在类判定之后、任何形状查询之前，因此默认关闭时这条路径一次世界 / 形状查询都不做。
            return false;
        }
        if (state == null || level == null || pos == null) {
            return false;
        }
        if (state.isAir() || !state.getFluidState().isEmpty() || state.canBeReplaced()) {
            return false;
        }
        if (state.getBlock().hasDynamicShape()) {
            // 动态外形：形状随位置 / 邻居 / 状态变化（线缆 / 管道族之外的这类方块也不可能是「稳定整格」）
            return false;
        }
        return state.isCollisionShapeFullBlock(level, pos);
    }

    /**
     * RS 侧：线缆/管道 {@code self} 朝 {@code towardOther} 的自动连接是否应被套壳快照阻断。
     *
     * <p><b>调用点</b>：{@code InWorldNetworkNodeContainerImpl#canAcceptIncomingConnection}
     * （{@code self} = 容器所属方块，{@code towardOther} = incomingDirection）与
     * {@link RsccWireLinkSearch} 的每一步 —— 与 {@link RsccCableCuts#isDisconnected} 并列，
     * 两者都只是「这一侧不通」的判据。</p>
     *
     * <p><b>坐标未加载时直接返回 false</b>：绝不为了一句判定去加载区块
     * （{@code getBlockState} 会对未加载区块触发加载，这里先 {@code isLoaded} 挡掉）。</p>
     */
    public static boolean blocksConnection(final Level level, final BlockPos self, final Direction towardOther) {
        final BlockPos other = self.relative(towardOther);
        if (!level.isLoaded(self) || !level.isLoaded(other)) {
            return false;
        }
        final BlockState selfState = level.getBlockState(self);
        final BlockState otherState = level.getBlockState(other);
        if (!RsccWireBlocks.isWire(selfState) || !RsccWireBlocks.isWire(otherState)) {
            return false;
        }
        return blockedBySnapshot(level, self, towardOther);
    }

    /**
     * Create 侧：流体管道 {@code self} 与 {@code other} 之间的管口是否应被套壳快照阻断。
     *
     * <p><b>调用点（两处，共用同一份判定）</b>：</p>
     * <ol>
     *     <li>{@code FluidPipeBlock#canConnectTo}（Create 判定「这一侧要不要连」的唯一入口：
     *     管口开放面 / 双侧网络遍历 / 外形都走它）——见 {@code mixin/create/FluidPipeBlockMixin}；
     *     <li>{@code FluidPipeBlock#updateBlockState}（算出<b>最终</b>的六向管口属性）返回前的兜底清理：
     *     Create 在该方法末尾有一条「无任何连接时按放置朝向硬开一对管口」的兜底
     *     （让孤管看起来像一根直管），它可以绕过 {@code canConnectTo} 直接开面。
     *     不清理的话，新放在被套壳管道旁边的那一节就会「渲染出一个准备连接的接缝臂」，
     *     而被套壳那节不理它 —— 正是用户看到的「渲染断层」。清理后：<b>逻辑不连的接缝，外形也不连</b>。</li>
     * </ol>
     * <p>该接口只给「管道 + 邻格」两个坐标，没有方向，因此这里用坐标差反推出方向；
     * 非相邻坐标（Create 不该这样调用）一律放行，绝不误伤。</p>
     */
    public static boolean blocksFluidPipeConnection(final Level level, final BlockPos self, final BlockPos other) {
        return blocksFluidPipeConnection(level.getBlockState(self), level, self, other);
    }

    /**
     * 同上，但<b>自己那一格的方块状态由调用方给出</b>。
     *
     * <p><b>为什么需要这个重载</b>：{@code getStateForPlacement → updateBlockState} 的那一瞬间
     * 这一格<b>还没有被放进世界</b>（{@code level.getBlockState(self)} 仍是空气 / 被替换的旧方块），
     * 若判定去世界里面取自己，就会取到空气而直接放行 —— 于是「刚放在被套壳管道旁边的那一节」
     * 依然会渲染出朝向被套壳那一节的接缝臂。把「正在计算的状态」传进来，判定才与接下来真正落地的
     * 状态一致（其余语义完全不变：仍然是「先看自己的快照、再看对面的快照」）。
     */
    public static boolean blocksFluidPipeConnection(final BlockState selfState, final Level level,
                                                    final BlockPos self, final BlockPos other) {
        if (!level.isLoaded(self) || !level.isLoaded(other)) {
            return false;
        }
        if (!isFluidPipe(selfState) || !isFluidPipe(level.getBlockState(other))) {
            return false;
        }
        final Direction towardOther = directionToward(self, other);
        return towardOther != null && blockedBySnapshot(level, self, towardOther);
    }

    /**
     * 语义核心：「这一处接缝」是否应被套壳快照阻断。
     *
     * <p>顺序 = 先看自己、再看对面：自己是套壳格就由自己的快照说了算；自己不是、对面是，
     * 就由对面的快照说了算（{@code towardOther.getOpposite()} 取的正是对面朝自己这一侧的那一位）。
     * 两侧都套壳时谁先谁后结论一致（两个快照必然一致，论证见类注释）；
     * 都没套壳则放行，把决定权交回 RS / Create 自己的规则。</p>
     */
    private static boolean blockedBySnapshot(final Level level, final BlockPos self, final Direction towardOther) {
        final Integer selfMask = RsccSheaths.sheathMask(level, self);
        if (selfMask != null) {
            return (selfMask & bit(towardOther)) == 0;
        }
        final BlockPos other = self.relative(towardOther);
        final Integer otherMask = RsccSheaths.sheathMask(level, other);
        return otherMask != null && (otherMask & bit(towardOther.getOpposite())) == 0;
    }

    /**
     * 这一处接缝<b>此刻</b>是否连通（套壳时算快照、以及快照自身的判定都用它，只有一份口径）。
     *
     * <p>连通 = ①两侧同属一个管道体系（RS 线缆类 ↔ RS 线缆类，或 Create 管道 ↔ Create 管道）；
     * ②没有被机械动力扳手断开（{@link RsccCableCuts}，它也是「这一侧不通」的既有判据）；
     * ③没有被<b>别的套壳格</b>的快照阻断。任一不满足即记「不连」。</p>
     *
     * <p><b>邻块未加载</b>时返回「不连」：无从判断时宁可记少，绝不凭空记出一条连接。</p>
     */
    public static boolean isSeamConnected(final Level level, final BlockPos self, final Direction towardOther) {
        final BlockPos other = self.relative(towardOther);
        if (!level.isLoaded(self) || !level.isLoaded(other)) {
            return false;
        }
        final BlockState selfState = level.getBlockState(self);
        final BlockState otherState = level.getBlockState(other);
        final boolean sameFamily = (RsccWireBlocks.isWire(selfState) && RsccWireBlocks.isWire(otherState))
            || (isFluidPipe(selfState) && isFluidPipe(otherState));
        if (!sameFamily) {
            return false;
        }
        if (RsccCableCuts.isDisconnected(level, self, towardOther)) {
            return false;
        }
        return !blockedBySnapshot(level, self, towardOther);
    }

    /**
     * 算出 {@code pos} 这一格<b>眼下的连接快照</b>：6 位掩码，位 = {@link Direction#ordinal()}，置位 = 连通。
     *
     * <p><b>必须在写入套壳记录之前调用</b>（见 {@link RsccSheaths#add} / {@link RsccSheaths#addAll}）：
     * 写入之后这一格就变成「被套壳格」，判定会改走它自己的快照 —— 拿写好的记录算自己，是自污染。</p>
     */
    public static int snapshotMask(final Level level, final BlockPos pos) {
        int mask = 0;
        for (final Direction direction : Direction.values()) {
            if (isSeamConnected(level, pos, direction)) {
                mask |= bit(direction);
            }
        }
        return mask;
    }

    /** 掩码位（与 {@link Direction#ordinal()} 一一对应，与 {@link RsccCableCuts} 的位序一致）。 */
    private static int bit(final Direction direction) {
        return 1 << direction.ordinal();
    }

    /** 由 {@code self} 指向相邻 {@code other} 的方向；非相邻（不该出现的调用）返回 {@code null}。 */
    @Nullable
    private static Direction directionToward(final BlockPos self, final BlockPos other) {
        for (final Direction direction : Direction.values()) {
            if (self.relative(direction).equals(other)) {
                return direction;
            }
        }
        return null;
    }

    /**
     * 套上 / 取下后，让<b>这一格与紧邻的六个格子</b>立刻重算连接。
     *
     * <p>被套住的那一格自己的连接臂 / 管口必须立刻重算（否则外形要等到下一次邻块变化的巧合时机），
     * 六个邻居同样要重算（它们看这一格的那一侧也要跟着变）。再多刷没有意义。</p>
     *
     * <p><b>RS 侧</b>：复用 {@link RsccCableCuts#refresh}（扳手分离线缆用的同一条链路），
     * 不重复实现一套 RS 内部调用，避免两处刷新逻辑将来各改各的
     * —— 它会重算连接臂 + 重建网络图 + 下发方块更新。</p>
     *
     * <p><b>Create 侧</b>：给本格与邻接的流体管道安排一次 1 tick 后的 HIGH 优先级 tick，
     * 这是 Create 自己在「管道放置 / 邻块变化」时用的同一条路
     * （{@code FluidPipeBlock#onPlace / #neighborChanged} →
     * {@code scheduleTick(pos, this, 1, TickPriority.HIGH)} → {@code FluidPropagator.propagateChangedPipe}），
     * 于是管口会在一个 tick 内完成「开放面重算 + 双侧网络重传播」，
     * 不需要我们直接调用 Create 的内部传播逻辑（更安全，也与其自身时序一致）。</p>
     */
    public static void refreshAround(final ServerLevel level, final BlockPos center) {
        refreshOne(level, center);
        for (final Direction direction : Direction.values()) {
            refreshOne(level, center.relative(direction));
        }
    }

    /**
     * 批量刷新：对 {@code positions} 里的每个坐标各重算一次连接（调用方负责传入<b>已去重</b>的集合）。
     *
     * <p><b>为什么需要它</b>：{@link #refreshAround} 一次刷「本格 + 六邻」共 7 格，单格套壳时正合适；
     * 连锁套壳一次可能套几十格，逐格调用会把中间的格子重复刷十几遍 —— RS 侧每次刷新都要重算网络图，
     * 代价不低。连锁路径因此先把「所有被套格 + 它们的六个邻格」并成一个去重集合，再用本方法各刷一次：
     * 语义与逐格 {@link #refreshAround} 完全等价（每个需要重算的坐标恰好被刷到一次），只是去掉了重复。</p>
     */
    public static void refreshBlocks(final ServerLevel level, final Iterable<BlockPos> positions) {
        for (final BlockPos pos : positions) {
            refreshOne(level, pos);
        }
    }

    /**
     * 让单个坐标重算一次连接：
     * <ol>
     *     <li><b>Create 管道</b>：先用 Create 自己的 {@code updateBlockState} 把六向管口属性重算一遍
     *     （见 {@link #recomputePipeFaces}）—— 这样「外形 / 渲染」与被套壳快照在<b>同一个 tick 内</b>
     *     就一致了，不必等下一次邻块变化的巧合；再排一次 HIGH 优先级 tick 让流体网络重传播；</li>
     *     <li><b>RS 线缆</b>：走既有刷新链路（连接臂外形 + 网络图 + 下发方块更新）。</li>
     * </ol>
     */
    private static void refreshOne(final ServerLevel level, final BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return;
        }
        final BlockState state = level.getBlockState(pos);
        if (isFluidPipe(state)) {
            recomputePipeFaces(level, pos, state);
            level.scheduleTick(pos, state.getBlock(), 1, TickPriority.HIGH);
        }
        RsccCableCuts.refresh(level, pos);
    }

    /**
     * 用 <b>Create 自己的</b> {@code FluidPipeBlock#updateBlockState} 重算这一格的六向管口属性，
     * 变了就写回。
     *
     * <p><b>为什么必须重算</b>：Create 的管道「逻辑」与「外形」读的是同一份东西 ——
     * 方块状态里的六个管口布尔值（{@code PROPERTY_BY_DIRECTION}）。它同时决定
     * ①流体网络的连通（{@code FluidPropagator}）、②模型里的接缝臂 / 管口外形、③选中框形状。
     * 套壳不改方块状态，所以套上 / 取下的那一刻它是<b>陈旧</b>的；这里主动重算一次，
     * 让这一份「唯一真源」与套壳快照对齐（重算内部走 {@code canConnectTo} →
     * {@link #blocksFluidPipeConnection}，与渲染用的是同一句话）。</p>
     *
     * <p><b>为什么调用 Create 的方法而不是自己算</b>：{@code updateBlockState} 里除了逐向判定，
     * 还有 Create 自己的兜底（孤管保留一对开口）与「被支架 / 忽略方向」等规则；
     * 自己算一遍就等于把那些规则抄一份，将来必然与 Create 漂移。</p>
     *
     * <p><b>写回的安全性</b>：只写<b>同一个方块</b>的新状态（{@code UPDATE_CLIENTS}），
     * 不放置 / 不销毁方块，也不碰方块实体 —— 管道里的流体、RS 的网络节点原样不动
     * （方块类型没变，方块实体不会被移除）。状态没变化时一个字节都不写。</p>
     *
     * <p><b>首选方向</b>取自这一格当前已经开着的那一面（没有则取 {@code UP}）：只在
     * 「这一格完全没有连接」时才会被 Create 的兜底用到，取当前开口是为了不改变孤管原本的观感。</p>
     */
    private static void recomputePipeFaces(final ServerLevel level, final BlockPos pos, final BlockState state) {
        if (!(state.getBlock() instanceof final FluidPipeBlock pipe)) {
            // 玻璃 / 智能 / 装壳后的管道没有「六向管口属性」，它们的管口由各自的类决定（无状态可重算）
            return;
        }
        final BlockState updated = pipe.updateBlockState(state, preferredOpenFace(state), null, level, pos);
        if (updated != state) {
            level.setBlock(pos, updated, Block.UPDATE_CLIENTS);
        }
    }

    /** 这一格当前开着的第一个面（没有则 {@code UP}）：仅用于 Create 孤管兜底时的首选方向。 */
    private static Direction preferredOpenFace(final BlockState state) {
        for (final Direction direction : Direction.values()) {
            if (Boolean.TRUE.equals(state.getValue(FluidPipeBlock.PROPERTY_BY_DIRECTION.get(direction)))) {
                return direction;
            }
        }
        return Direction.UP;
    }
}
