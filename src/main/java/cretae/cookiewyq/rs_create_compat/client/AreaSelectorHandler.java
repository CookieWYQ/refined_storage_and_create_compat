package cretae.cookiewyq.rs_create_compat.client;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.client.screen.AreaSelectorScreen;
import cretae.cookiewyq.rs_create_compat.network.AreaSelectorEditPacket;
import cretae.cookiewyq.rs_create_compat.support.Area;
import cretae.cookiewyq.rs_create_compat.support.AreaSelection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;

import java.util.List;

/**
 * 区域选定器的<b>世界内框选手势</b>（对应设计文档 §3.4 A）。
 *
 * <h2>为什么必须自己写一份（不能复用蓝图的 handler）</h2>
 * <p>Create 的 {@code SchematicAndQuillHandler#isActive()} 是 <b>private</b> 且硬编码只认主手的
 * {@code SCHEMATIC_AND_QUILL}，没有「再挂一组框」的接口。这里照抄它的<b>套路</b>
 * （右键两点 + Ctrl 滚轮 + Outliner 画框 + 只存客户端内存），交互与蓝图 1:1。</p>
 *
 * <h2>手势（用户要求「每一处都能在文档里写清」）</h2>
 * <table>
 *     <tr><th>手势</th><th>行为</th></tr>
 *     <tr><td>右键方块（第 1 次）</td><td>记下 A 角（预览框实时跟随准星）</td></tr>
 *     <tr><td>右键方块（第 2 次）</td><td>预检维度 / 重复 / 体积 / 上限后以 B 角提交<b>一个新区域</b>
 *     （追加到选定器、自动选中它）；预检不过就地说明，不发包、不宣告成功</td></tr>
 *     <tr><td>潜行 + 右键方块</td><td>放弃当前未完成的 A 角（不写入任何数据）</td></tr>
 *     <tr><td>右键空气（<b>不潜行</b>，第 112 轮新增）</td><td>打开「区域列表」界面</td></tr>
 *     <tr><td>潜行 + 右键空气</td><td>打开「区域列表」界面（同效；手柄 / 触屏也能用的老入口）</td></tr>
 *     <tr><td>Ctrl + 滚轮（有 A 角）</td><td>沿视线主轴推 / 拉正在框的盒子（Shift ×5）</td></tr>
 *     <tr><td>Ctrl + 滚轮（无 A 角）</td><td>调整<b>当前选中区域</b>在该主轴上的大小（Shift ×5）</td></tr>
 * </table>
 *
 * <p><b>第 112 轮为什么加了「右键空气」入口</b>：用户反馈「无法正常的单独给某一个区域标记，
 * 就非得把这个标记放在方块中进行吗这样子感觉很麻烦，切换的时候更麻烦」。旧版打开列表只有
 * 「潜行 + 右键空气」，而玩家在基地里几乎总是对着方块 ⇒ 列表打不开 ⇒ 只能把选定器插进机器
 * 再进机器界面（= 用户说的「非得放在方块里」）。现在对着空气右键即可，且列表里点某行的
 * 「区域标记」就能<b>直接</b>给那个区域配过滤标记（{@code client/screen/AreaMarkScreen}）。</p>
 *
 * <p><b>待提交的 A 角只存在客户端内存</b>（照蓝图的 {@code firstPos}）：选一半跑掉 / 退出游戏
 * 不会污染物品数据。真正落盘的只有「已提交的区域」，走
 * {@link AreaSelectorEditPacket} 由服务端校验后写入数据组件。</p>
 *
 * <p>颜色 / 渲染统一由 {@link AreaOutline} 负责（橙色 {@code 0xFF9F1C}，与蓝图蓝区分）。</p>
 */
public final class AreaSelectorHandler {
    private static final String MSG = "message.rs_create_compat.area_selector.";

    /** 未提交的 A 角（客户端内存；null = 没有正在框的盒子）。 */
    private static BlockPos pendingFirst;
    /** Ctrl 滚轮对「正在框的盒子」的推拉量（格，沿视线主轴；提交时加到 B 角上）。 */
    private static int pendingExtend;
    /** 世界叠加层里当前画着的已提交区域个数（用于清掉多出来的旧框）。 */
    private static int shownAreas;
    /** 是否已经为某个「未完成框」喂过预览（用于一次性清理）。 */
    private static boolean previewShown;

    private AreaSelectorHandler() {
    }

    /** 主手是否拿着区域选定器。 */
    private static boolean isHoldingSelector(final Minecraft minecraft) {
        if (minecraft.player == null) {
            return false;
        }
        final ItemStack mainHand = minecraft.player.getMainHandItem();
        return mainHand.is(RS_Create_Compat.AREA_SELECTOR.get());
    }

    /** 当前生效的框选状态（鼠标 / 滚轮 / tick 三个入口共用同一判据）。 */
    private static boolean isActive(final Minecraft minecraft) {
        return minecraft.player != null && minecraft.level != null
            && minecraft.screen == null && isHoldingSelector(minecraft);
    }

    // ==================== 每 tick：喂 Outliner + 清理 ====================

    public static void onClientTick(final ClientTickEvent.Post event) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (!isActive(minecraft)) {
            hideAll();
            return;
        }
        final AreaSelection selection = AreaSelection.get(minecraft.player.getMainHandItem());
        // ① 已提交区域：拿在手上时常亮（橙色；与「查看范围」同一个颜色常量）
        // <b>去重</b>（第 96 轮）：旧存档可能已经存了完全重复的区域（本轮之前没有拦截），
        // 同一块地喂两个 Outliner slot 会叠画两份（看起来就是框变粗 / 闪烁）—— 玩家投诉的正是这个。
        final List<Area> distinct = selection.distinctAreas();
        final int count = Math.min(distinct.size(), AreaSelection.ABSOLUTE_MAX_AREAS);
        for (int i = 0; i < count; i++) {
            AreaOutline.chase(AreaOutline.slot("world/" + i), distinct.get(i));
        }
        for (int i = count; i < shownAreas; i++) {
            AreaOutline.remove(AreaOutline.slot("world/" + i));
        }
        shownAreas = count;
        // ② 未提交的框：跟随准星的实时预览
        if (pendingFirst == null) {
            if (previewShown) {
                AreaOutline.remove(AreaOutline.slot("pending"));
                previewShown = false;
            }
            return;
        }
        final BlockPos second = previewCorner(minecraft);
        if (second == null) {
            AreaOutline.chaseBox(AreaOutline.slot("pending"), new Area(pendingFirst.getX(), pendingFirst.getY(),
                pendingFirst.getZ(), pendingFirst.getX(), pendingFirst.getY(), pendingFirst.getZ()).toAabb());
        } else {
            AreaOutline.chaseBox(AreaOutline.slot("pending"),
                AreaOutline.previewBox(pendingFirst, second));
        }
        previewShown = true;
    }

    /** 预览 / 提交共用的 B 角：准星命中格 + Ctrl 滚轮的推拉量（沿视线主轴）。 */
    private static BlockPos previewCorner(final Minecraft minecraft) {
        final BlockPos hit = targetedPos(minecraft);
        if (hit == null) {
            return null;
        }
        if (pendingExtend == 0) {
            return hit;
        }
        return switch (dominantAxis(minecraft.player.getLookAngle())) {
            case 1 -> hit.offset(0, pendingExtend, 0);
            case 2 -> hit.offset(0, 0, pendingExtend);
            default -> hit.offset(pendingExtend, 0, 0);
        };
    }

    /** 准星命中的方块坐标（原版 {@code hitResult}，不用 Create 的 RaycastHelper）。 */
    private static BlockPos targetedPos(final Minecraft minecraft) {
        final HitResult hit = minecraft.hitResult;
        return hit != null && hit.getType() == HitResult.Type.BLOCK
            ? ((BlockHitResult) hit).getBlockPos() : null;
    }

    /** 视线主轴（0=X / 1=Y / 2=Z）。 */
    private static int dominantAxis(final Vec3 look) {
        final double x = Math.abs(look.x);
        final double y = Math.abs(look.y);
        final double z = Math.abs(look.z);
        if (y >= x && y >= z) {
            return 1;
        }
        return z >= x ? 2 : 0;
    }

    /** 清掉世界叠加层里的全部框 + 未提交状态（换物品 / 开界面 / 退出时调用）。 */
    private static void hideAll() {
        if (pendingFirst != null || previewShown) {
            AreaOutline.remove(AreaOutline.slot("pending"));
            previewShown = false;
        }
        if (shownAreas > 0) {
            for (int i = 0; i < shownAreas; i++) {
                AreaOutline.remove(AreaOutline.slot("world/" + i));
            }
            shownAreas = 0;
        }
        pendingFirst = null;
        pendingExtend = 0;
    }

    // ==================== 右键 ====================

    /**
     * {@code InputEvent.MouseButton.Pre}：返回 true 表示「这一次右键被本工具消费」，
     * 调用方（ClientInit）据此 {@code event.setCanceled(true)} —— 与 Create 的
     * {@code InputEvents.onMouseInput} 逐字同构（右键不再传给方块 / 物品）。
     */
    public static boolean onMouseButton(final int button, final boolean pressed) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (!pressed || button != 1 || minecraft.screen != null || !isActive(minecraft)) {
            return false;
        }
        final BlockPos hit = targetedPos(minecraft);
        if (minecraft.player.isShiftKeyDown()) {
            if (hit == null) {
                // 潜行 + 右键空气 = 打开区域列表（键盘无关，手柄 / 触屏也能用）
                hideAll();
                minecraft.setScreen(new AreaSelectorScreen(null));
            } else if (pendingFirst != null) {
                // 潜行 + 右键方块 = 放弃当前未完成的框（蓝图同款）
                pendingFirst = null;
                pendingExtend = 0;
                AreaOutline.remove(AreaOutline.slot("pending"));
                previewShown = false;
                message(minecraft, MSG + "cancelled");
            } else {
                // 潜行 + 右键方块、但根本没有「未完成的框」可取消：这次右键仍然被本工具消费
                // （不让它落到方块 / 物品上，与框选手势保持一致），但必须给一条提示 ——
                // 否则玩家只会看到「潜行右键毫无反应」，以为工具坏了。
                message(minecraft, MSG + "sneak_block_hint");
            }
            return true;
        }
        if (hit == null) {
            // 第 112 轮：<b>不潜行</b>的右键空气也打开区域列表。
            // 用户原话：「无法正常的单独给某一个区域标记，就非得把这个标记放在方块中进行吗这样子感觉很麻烦」——
            // 旧版只有「潜行 + 右键空气」这一个入口，而玩家站在基地里几乎总是对着方块，
            // 于是列表根本打不开，只能把选定器插进机器、再进机器界面（= 用户说的「非得放在方块里」）。
            // 有未完成的 A 角时仍然只提示（不然「想取消框」会变成「开了个界面」）。
            if (pendingFirst != null) {
                message(minecraft, MSG + "need_target");
                return true;
            }
            hideAll();
            minecraft.setScreen(new AreaSelectorScreen(null));
            return true;
        }
        final ItemStack stack = minecraft.player.getMainHandItem();
        final AreaSelection selection = AreaSelection.get(stack);
        if (pendingFirst == null) {
            pendingFirst = hit;
            pendingExtend = 0;
            message(minecraft, MSG + "first", hit.getX(), hit.getY(), hit.getZ());
            return true;
        }
        final BlockPos second = previewCorner(minecraft);
        final Area area = second == null ? null : Area.of(pendingFirst, second);
        if (area == null) {
            return true;
        }
        final String invalid = area.invalidReason(AreaSelection.maxVolume());
        if (invalid != null) {
            // 客户端先拦一次：不合法就不发（服务端还会再校验一次，双保险）
            message(minecraft, MSG + "too_large", AreaSelection.maxVolume(), Area.MAX_SIDE);
            return true;
        }
        if (selection.areaCount() >= AreaSelection.maxAreas()) {
            message(minecraft, MSG + "limit", AreaSelection.maxAreas());
            return true;
        }
        // 第 119 轮 C-3：与 `AreaSelectorScreen#createArea` / 服务端 `ACTION_ADD` 分支**同源**的预检。
        // 主手路径没有菜单快照来回传拒绝原因（服务端只 `broadcastFullState()`，不弹字），
        // 若不先拦，玩家看到的会是下面那句「已提交」而服务端其实什么都没写。
        final net.minecraft.resources.ResourceLocation here = minecraft.level.dimension().location();
        if (!selection.matchesDimension(here)) {
            message(minecraft, MSG + "dimension", String.valueOf(selection.dimension()));
            return true;
        }
        if (selection.hasGeometry(area, -1)) {
            // 同一块地重复框（最自然的手滑）：服务端会以 REJECT_DUPLICATE 丢掉，这里就地说明。
            message(minecraft, MSG + "duplicate");
            return true;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new AreaSelectorEditPacket(-1, AreaSelectorEditPacket.ACTION_ADD, -1, area));
        pendingFirst = null;
        pendingExtend = 0;
        AreaOutline.remove(AreaOutline.slot("pending"));
        previewShown = false;
        // 只有走完上面全部预检（= 与服务端拒绝条件逐条对齐）才会到这里，所以这句「已提交」
        // 不会再与事实相反；服务端即使因极端竞态拒绝，也会 `inventoryMenu.broadcastFullState()`
        // 把物品推回权威值（第 113 轮 D7），界面下一帧就被纠正。
        message(minecraft, MSG + "committed", area.volume(), area.describe());
        return true;
    }

    // ==================== Ctrl + 滚轮 ====================

    /** {@code InputEvent.MouseScrollingEvent}：返回 true = 消费这次滚轮。 */
    public static boolean onScroll(final double delta) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || !isActive(minecraft) || !Screen.hasControlDown()) {
            return false;
        }
        final int step = Screen.hasShiftDown() ? 5 : 1;
        final int direction = delta > 0 ? 1 : -1;
        if (pendingFirst != null) {
            pendingExtend = Math.max(-Area.MAX_SIDE,
                Math.min(Area.MAX_SIDE, pendingExtend + direction * step));
            final BlockPos second = previewCorner(minecraft);
            if (second != null) {
                final Area preview = Area.of(pendingFirst, second);
                message(minecraft, MSG + "preview", preview.describe(), preview.volume());
            }
            return true;
        }
        return resizeSelected(minecraft, direction * step);
    }

    /** 无未完成框时：Ctrl 滚轮调整当前选中区域（沿视线主轴，Shift ×5）。 */
    private static boolean resizeSelected(final Minecraft minecraft, final int delta) {
        final ItemStack stack = minecraft.player.getMainHandItem();
        final AreaSelection selection = AreaSelection.get(stack);
        final Area selected = selection.selected();
        if (selected == null || selection.selectedIndex() < 0) {
            message(minecraft, MSG + "no_selection");
            return true;
        }
        // 第 119 轮 C-3（同一类的第二处入口）：Ctrl + 滚轮也是「先发包、立刻宣告已调整」。
        // 维度 / 去重预检与服务端 `ACTION_REPLACE` 分支同源，避免被拒后仍显示「已把选中区域调整为 …」。
        if (!selection.matchesDimension(minecraft.level.dimension().location())) {
            message(minecraft, MSG + "dimension", String.valueOf(selection.dimension()));
            return true;
        }
        final int axis = dominantAxis(minecraft.player.getLookAngle());
        final long size = axis == 1 ? selected.sizeY() : axis == 2 ? selected.sizeZ() : selected.sizeX();
        final long nextSize = size + delta;
        if (nextSize < 1L || nextSize > Area.MAX_SIDE) {
            return true;
        }
        final Area resized = switch (axis) {
            case 1 -> new Area(selected.minX(), selected.minY(), selected.minZ(),
                selected.maxX(), selected.maxY() + delta, selected.maxZ());
            case 2 -> new Area(selected.minX(), selected.minY(), selected.minZ(),
                selected.maxX(), selected.maxY(), selected.maxZ() + delta);
            default -> new Area(selected.minX(), selected.minY(), selected.minZ(),
                selected.maxX() + delta, selected.maxY(), selected.maxZ());
        };
        if (resized.invalidReason(AreaSelection.maxVolume()) != null) {
            message(minecraft, MSG + "too_large", AreaSelection.maxVolume(), Area.MAX_SIDE);
            return true;
        }
        // 去重：调整后的几何若正好等于另一块地，服务端会以 REJECT_DUPLICATE 丢掉（excludeIndex = 自己）。
        if (selection.hasGeometry(resized, selection.selectedIndex())) {
            message(minecraft, MSG + "duplicate");
            return true;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new AreaSelectorEditPacket(-1, AreaSelectorEditPacket.ACTION_REPLACE,
                selection.selectedIndex(), resized));
        message(minecraft, MSG + "resized", resized.volume());
        return true;
    }

    private static void message(final Minecraft minecraft, final String key, final Object... args) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(key, args), true);
        }
    }
}
