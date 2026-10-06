package cretae.cookiewyq.rs_create_compat.client;

import com.mojang.logging.LogUtils;
import cretae.cookiewyq.rs_create_compat.network.RefreshCamouflagePacket;
import cretae.cookiewyq.rs_create_compat.support.RsccCamouflage;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * 外壳显示的<b>客户端收尾</b>：K 键的「只显示框架 / 完整显示填充方块」一翻转，就把<b>受影响的那些伪装格</b>
 * 重新标脏（下一次重建就会重新取数、重新烘外壳）。
 *
 * <h2>为什么必须有它</h2>
 * <p>「完整显示填充方块 / 只显示框架」的区别只体现在<b>外壳的几何</b>上，而几何是<b>烘焙产物</b>
 * （见 {@code client/model/CamouflageShellModel}）—— 开关翻转不会自动让已经烘好的区块网格重算。</p>
 *
 * <h2>2026-10-01（本轮修「切换时整屏一白 + 强烈卡顿」）：删除 {@code levelRenderer.allChanged()}</h2>
 * <p><b>根因</b>：旧实现每次切换都调 {@code Minecraft#levelRenderer#allChanged()}。那是<b>整片重烘</b> ——
 * 它把当前视野内<b>全部</b>已烘好的区段<b>一次性作废并重建</b>（连带方块实体、实体、光照重排）。
 * 重建期间渲染器的区段缓冲被整批丢弃，于是那一瞬<b>整个界面 / 整个世界都不渲染</b>（用户原话
 * 「整个界面啥也不渲染、整个游戏啥也不渲染」「屏幕一白」），随后才一帧帧补回来 ——
 * 开销与<b>视野内的全部区块</b>成正比，与「有几格伪装」无关。</p>
 * <p><b>修法</b>：只重建<b>真正受影响的方块</b>。为此本类维护一份「客户端见过的伪装格坐标」表
 * （由 {@link RefreshCamouflagePacket} 在每次下发「请重烘」信号时顺手登记，见
 * {@link #track(BlockPos)}），切换时逐格：
 * ① 清掉那一格的方块实体模型数据缓存（开关就是被烘进模型数据的，见
 * {@code CamouflageShellModel#MATERIAL_HIDDEN_PROPERTY}）；② 每<b>区段</b>只标脏一次
 * （同一区段内多格共享同一次重建）。全程<b>不调用 allChanged、不触发资源重载、不做任何全屏级操作</b>。</p>
 * <p><b>表为什么是「只增不精确删」</b>：它只是一份「可能有伪装」的<b>超集</b>；每次切换顺手把已经
 * 不再伪装的坐标剔除（{@link #refreshTracked} 里的惰性清理），因此不会无限增长，也不会漏。</p>
 *
 * <p><b>为什么用「轮询一个布尔位」而不是在收包处直接重烘</b>：S2C 收包的处理体所在的类是
 * <b>双端都会加载</b>的（它被注册进 {@code playToClient}），因此那里绝不能引用任何
 * {@code net.minecraft.client.*} 的类（专用服务端上会解析失败）。本类是纯粹的客户端类
 * （只被 {@code ClientInit} 的客户端初始化引用，服务端不会加载它）。</p>
 * <p><b>为什么开关是「按玩家」的</b>：区块网格是<b>每个客户端各自烘焙</b>的，
 * 因此这一位开关只影响本玩家看到的画面 —— 与「甲按 K 不影响乙看到的同一格」这条要求一致。</p>
 */
public final class CamouflageShellDisplay {
    /** 诊断日志（只在切换那一次打一条，不刷屏）。 */
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    /** 上一次已经应用到网格上的开关值（只在本客户端有意义）。 */
    private static boolean appliedHidden;

    /**
     * 本客户端见过的「伪装格」坐标（{@link BlockPos#asLong()} 打包；只在客户端主线程访问）。
     * <p>由 {@link RefreshCamouflagePacket} 的收包路径登记（也会登记「刚被取下」的坐标，
     * 那种坐标会在下一次切换时被惰性剔除）。</p>
     */
    private static final Set<Long> TRACKED = new HashSet<>();

    /** 上一次切换真正重建的方块数 / 区段数（只为诊断日志与自检量化，不参与任何逻辑）。 */
    private static int lastRefreshedBlocks;
    private static int lastRefreshedSections;

    private CamouflageShellDisplay() {
    }

    /** 由 {@link RefreshCamouflagePacket} 的客户端收包路径调用：登记一个「伪装数据变过」的坐标。 */
    public static void track(final BlockPos pos) {
        if (pos != null) {
            TRACKED.add(pos.asLong());
        }
    }

    /** 游戏总线（{@code ClientTickEvent.Post}）：开关一变就<b>只</b>重建受影响的那些格。 */
    public static void onClientTick(final ClientTickEvent.Post event) {
        final Minecraft minecraft = Minecraft.getInstance();
        final boolean hidden = RsccCamouflage.isMaterialHidden();
        if (hidden == appliedHidden) {
            return; // 绝大多数 tick 在这里返回（一次布尔比较，零分配）
        }
        appliedHidden = hidden;
        final Level level = minecraft.level;
        if (level == null) {
            return;
        }
        refreshTracked(level);
        LOGGER.debug("camouflage K toggle -> re-baked {} block(s) / {} section(s) (never full reload)",
            lastRefreshedBlocks, lastRefreshedSections);
    }

    /**
     * 逐格重建：清掉每格的模型数据缓存 + 每区段标脏一次。
     *
     * <p><b>绝不做的事</b>：不调 {@code levelRenderer.allChanged()}、不重载资源、不整片作废 ——
     * 因此不存在「整帧不渲染」的窗口（用户第 1 条要的「平滑」）。</p>
     *
     * @return 真正重建的方块数（同时把它与区段数记进诊断字段）
     */
    private static int refreshTracked(final Level level) {
        final Set<SectionPos> sections = new HashSet<>();
        int blocks = 0;
        final Iterator<Long> iterator = TRACKED.iterator();
        while (iterator.hasNext()) {
            final BlockPos pos = BlockPos.of(iterator.next());
            if (!RsccCamouflage.isCamouflaged(level, pos)) {
                iterator.remove(); // 惰性清理：已经不再伪装的坐标直接剔掉
                continue;
            }
            final BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                // K 键这一态被烘进了模型数据（见 CamouflageShellModel），必须先让这一格重新取数
                be.requestModelDataUpdate();
            }
            blocks++;
            // 每个区段只标脏一次：同一区段里的多格共享同一次重建
            if (sections.add(SectionPos.of(pos))) {
                final BlockState state = level.getBlockState(pos);
                level.sendBlockUpdated(pos, state, state, 16);
            }
        }
        lastRefreshedBlocks = blocks;
        lastRefreshedSections = sections.size();
        return blocks;
    }

    /** 上一次切换重建的方块数（诊断 / 自检用）。 */
    public static int lastRefreshedBlocks() {
        return lastRefreshedBlocks;
    }

    /** 上一次切换重建的区段数（诊断 / 自检用）。 */
    public static int lastRefreshedSections() {
        return lastRefreshedSections;
    }
}
