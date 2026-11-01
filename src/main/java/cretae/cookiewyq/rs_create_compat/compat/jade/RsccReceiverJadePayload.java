package cretae.cookiewyq.rs_create_compat.compat.jade;

import cretae.cookiewyq.rs_create_compat.block.entity.NetworkResourceReceiverBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>网络资源接收器的 Jade 提示里「Jade 自己答不出来」的那一行</b>（只有一行：接收器名）。
 *
 * <h2>内部缓存内容为什么<b>不</b>在这里显示（第 95 轮用户口径）</h2>
 * <p>用户原话：「<i>让 Jade 显示里面有什么东西的这些方块，你的作用只是把这一个接口暴露出去，
 * 并不需要你自己写……按下 Shift 显示详情什么的这玩意 Jade 已经帮你实现了</i>」。</p>
 * <p>复核结论（<b>字节码证据</b>，jar 在 {@code libs/jade-15.10.5+neoforge.jar}）：</p>
 * <ul>
 *     <li>{@code snownee/jade/util/CommonProxy#findItemHandler(Accessor)} 调
 *     {@code Level#getCapability(Capabilities.ItemHandler.BLOCK, pos, state, blockEntity, null)}
 *     —— 最后一个参数（朝向 / 上下文）是 {@code aconst_null}，即 <b>side = null</b>；</li>
 *     <li>{@code CommonProxy#wrapFluidStorage(Accessor)} 同样拿
 *     {@code Capabilities.FluidHandler.BLOCK} + {@code null}；</li>
 *     <li>这两个方法由 {@code ItemStorageProvider} / {@code FluidStorageProvider} 的
 *     {@code IServerDataProvider} 在<b>服务端</b>调用，内容随 Jade 自己的通道下发给
 *     正在看这一格的玩家，且 Jade 自己会做「按住 Shift 展开」。</li>
 * </ul>
 * <p>本模组的接收器在 {@code NetworkResourceReceiverBlockEntity#registerCapabilities} 里对
 * {@code ItemHandler.BLOCK} / {@code FluidHandler.BLOCK} <b>任意方向（含 null）</b>返回只读视图，
 * 因此「里面有什么」这件事 <b>Jade 自己就能显示</b>，不需要本模组再写一遍 ——
 * 之前那套「概括 + 按住 Shift 列明细」的代码已按用户要求删除。</p>
 *
 * <h2>那为什么还剩这一行</h2>
 * <p>「这台接收器叫什么名字 / 有没有命名」是<b>本方块自己的状态</b>，不在任何 inventory 里，
 * Jade 的通用存储 provider 结构上取不到（它只问容器内容）。而未命名对玩家是硬后果：
 * 请求器根本找不到它、缓存不会往外输出。所以保留一个<b>只加文本、不判任何按键</b>的 provider，
 * 把这一句补上；其它一切显示都交回 Jade。</p>
 *
 * <p><b>只读</b>：{@link #write} 只调 {@code getReceiverDisplayName()} /
 * {@code isProperlyConfigured()}，不改任何状态、不触发搬运。</p>
 *
 * <p><b>dist 安全</b>：本类只用公共类型（NBT / {@code Component} / 方块实体），
 * 不引用 {@code snownee.jade.*} 也不引用 {@code net.minecraft.client.*}。</p>
 */
public final class RsccReceiverJadePayload {
    /** 服务端数据里的根复合标签（本模组专用；与总线载荷的 {@code rscc_bus} 不冲突）。 */
    public static final String ROOT = "rscc_receiver";
    /** 接收器显示名（有名字用名字，没名字用坐标串 —— 与界面同一份 {@code getReceiverDisplayName()}）。 */
    private static final String NAME = "name";
    /** 是否已命名（未命名 = 完全不可用，见 {@code isProperlyConfigured()}）。 */
    private static final String CONFIGURED = "configured";

    /** 提示行语言键前缀（中英两套见 {@code assets/rs_create_compat/lang/*.json}）。 */
    private static final String LANG = "gui.rs_create_compat.jade_receiver.";

    private RsccReceiverJadePayload() {
    }

    // ==================== 服务端：写 ====================

    /**
     * 服务端采集：只把「名字 + 是否已命名」写进 Jade 的服务端数据。
     * <p>非接收器 / 客户端 / 无方块实体 ⇒ 一个键都不写（客户端 {@link #hasData} 为假，
     * 因此不会给别的方块多出任何行）。</p>
     */
    public static void write(final CompoundTag out, final Level level, final BlockPos pos,
                             @Nullable final BlockEntity blockEntity) {
        if (out == null || level == null || level.isClientSide() || pos == null) {
            return;
        }
        if (!(blockEntity instanceof NetworkResourceReceiverBlockEntity receiver)) {
            return;
        }
        final CompoundTag root = new CompoundTag();
        root.putString(NAME, receiver.getReceiverDisplayName());
        root.putBoolean(CONFIGURED, receiver.isProperlyConfigured());
        out.put(ROOT, root);
    }

    // ==================== 客户端：读 + 文案 ====================

    /** 服务端数据里有没有本模组那条根标签（没有 ⇒ 一行都不加，完全放行）。 */
    public static boolean hasData(@Nullable final CompoundTag serverData) {
        return serverData != null && !serverData.getCompound(ROOT).isEmpty();
    }

    /**
     * 服务端数据 → 提示行（纯函数：NBT 进、{@code Component} 出）。
     *
     * <p><b>不看按键状态</b>：本方法没有 {@code expanded} 参数，provider 也不查 Shift ——
     * 展开 / 收起由 Jade 自己按用户按键决定（用户明确要求「不要自己写按 Shift」）。</p>
     */
    public static List<Component> lines(@Nullable final CompoundTag serverData) {
        final List<Component> out = new ArrayList<>();
        if (!hasData(serverData)) {
            return out;
        }
        final CompoundTag root = serverData.getCompound(ROOT);
        if (root.getBoolean(CONFIGURED)) {
            out.add(Component.translatable(LANG + "name", root.getString(NAME)));
        } else {
            // 未命名 = 完全不可用。必须直说，否则玩家会以为「收到了但没显示」
            out.add(Component.translatable(LANG + "unnamed"));
        }
        return out;
    }
}
