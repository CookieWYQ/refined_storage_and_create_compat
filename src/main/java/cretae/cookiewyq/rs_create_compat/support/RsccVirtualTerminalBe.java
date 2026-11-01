package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 「虚拟方块实体」的<b>唯一哨兵坐标</b>与<b>唯一判据</b>。
 *
 * <h2>它是什么</h2>
 * <p>高级远程多功能终端（物品）打开样板终端 / 合成仓管理器 / 单元样板管理舱 / 序列装配样板终端时，
 * 本模组用反射在<b>内存里</b> new 一个真实的方块实体（{@code PatternGridBlockEntity} /
 * {@code AutocrafterManagerBlockEntity} / 本模组自己的两个终端方块实体），<b>不放进世界</b> ——
 * 这样 RS 原版的「方块实体版菜单」就能照常构造。这类对象就是「虚拟方块实体」。</p>
 *
 * <h2>为什么要换掉 {@code BlockPos.ZERO} 这个旧哨兵（用户实机反馈的根因）</h2>
 * <p>旧实现的判据是：</p>
 * <pre>
 * be.getBlockPos().equals(BlockPos.ZERO) &amp;&amp; be.getLevel().getBlockEntity(BlockPos.ZERO) != be
 * </pre>
 * <p>它把「是不是虚拟的」<b>押在世界坐标原点 (0,0,0) 上</b>，于是这条判定链上出现了两个
 * <b>与玩家无关、却会随玩家移动而变</b>的世界状态：</p>
 * <ol>
 *     <li><b>原点那一格有没有方块实体</b>：世界里真有一台方块实体落在 (0,0,0)（原点常在出生点 /
 *     玩家基地附近，本工程的测试存档基地就压在原点旁）时，判据立刻变成「不是虚拟的」，随后落到
 *     {@code player.distanceToSqr(0.5, 0.5, 0.5) &lt;= 64} 这条兜底上 —— 玩家在 y=-60 的基地里，
 *     距原点 50 多格，恒为假 ⇒ <b>菜单打开后 1 tick 就被服务端 {@code ServerPlayer#tick()} 关掉</b>，
 *     玩家看到的就是「按快捷键打不开」；</li>
 *     <li><b>原点所在区块加载与否</b>：{@code Level#getBlockEntity} 对<b>未加载</b>的区块返回 null，
 *     而「哪些区块已加载」完全由玩家位置与区块票据决定 —— <b>玩家一走动，这一格的结果就可能翻转</b>，
 *     于是同一个界面会在「开着」与「1 tick 被关掉」之间跳变。这正是玩家报告的
 *     「站在原地怎么按都打不开，动一下（前进后退）就打开了」的来源：
 *     <b>能不能打开取决于玩家位置，而不是取决于按键</b>。</li>
 * </ol>
 *
 * <h2>现在的判据（位置无关、零世界访问、零玩家状态）</h2>
 * <p>虚拟方块实体一律建在 {@link #SENTINEL}：y = {@value #SENTINEL_Y}，落在<b>所有原版维度
 * 建筑高度之外</b>（主世界 -64..320，下界 / 末地 0..256）。因此：</p>
 * <ul>
 *     <li>{@link #isVirtual(BlockEntity)} 只比一个常量坐标 —— <b>不读世界、不查区块、不看玩家</b>，
 *     不可能随玩家移动 / 区块装卸而翻转；</li>
 *     <li>即使别处仍去问世界，{@code Level#isOutsideBuildHeight(SENTINEL)} 也会让
 *     {@code getBlockEntity} 直接返回 null（不触碰任何区块），结论与「世界里有什么」无关；</li>
 *     <li>哨兵与真实坐标不可能撞车：建筑高度之外的方块实体不存在（世界不可能在 y=-2048 放东西）。</li>
 * </ul>
 *
 * <h2>为什么必须是<b>唯一</b>一份</h2>
 * <p>「建虚拟方块实体」（{@code AdvancedRemoteTerminalItem} 的四个模式）与「判定它是不是虚拟的」
 * （四个界面 / 两个 Mixin）必须是<b>同一个常量</b>，否则一边换、一边不换就会出现
 * 「界面建出来了但 1 tick 被关掉」这种最难查的分叉。本类就是那个唯一来源。</p>
 */
public final class RsccVirtualTerminalBe {
    /**
     * 哨兵 y。必须落在<b>所有维度</b>的建筑高度之外：主世界最低 -64、下界 / 末地最低 0，
     * 取 -2048 留足余量（自定义维度若把下限压到 -2048 以下，{@code isVirtual} 仍只比常量，不受影响；
     * 受影响的只是「万一有人去问世界」那条兜底，而本条已经不再依赖它）。
     */
    public static final int SENTINEL_Y = -2048;

    /** 虚拟方块实体的唯一坐标（x / z 恒为 0，y 在建筑高度之外）。 */
    public static final BlockPos SENTINEL = new BlockPos(0, SENTINEL_Y, 0);

    private RsccVirtualTerminalBe() {
    }

    /** 建虚拟方块实体时使用的坐标（<b>所有</b>模式都必须用这一个）。 */
    public static BlockPos sentinel() {
        return SENTINEL;
    }

    /**
     * 这个方块实体是不是「用 {@link #sentinel()} 建出来的、不活在世界里的」虚拟方块实体。
     * <p><b>纯常量比较</b>：不读 {@code getLevel()}、不查任何区块、不看玩家位置 —— 因此
     * 「界面能不能保持打开」与玩家是否移动<b>结构上无关</b>。</p>
     */
    public static boolean isVirtual(final BlockEntity blockEntity) {
        return blockEntity != null && SENTINEL.equals(blockEntity.getBlockPos());
    }
}
