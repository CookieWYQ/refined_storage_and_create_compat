package cretae.cookiewyq.rs_create_compat.support;

/**
 * 桥接接口：由 Mixin（注入 RS 自动合成仓方块实体 AutocrafterBlockEntity）实现，
 * 让其它代码能类型安全地读写“内部存储开关”。
 * <p>
 * 注意：本接口不是 Mixin，只是 duck-typing 用的普通接口；实现方是 Mixin 类，
 * 运行期目标类会 implements 它，因此可以安全地 instanceof 判断后强转。
 * <p>
 * 该接口刻意放在 support 包而不是 mixin 包：mixin 包被 mixins.json 声明为
 * Mixin 专用包，普通类放在其中被外部直接引用会抛 IllegalClassLoadError。
 */
public interface RsccAutocrafterStorage {
    /** 该自动合成仓的内部存储是否启用（默认值取自 Config.autocrafterStorageEnabled）。 */
    boolean rscc$isStorageEnabled();

    /** 设置该自动合成仓的内部存储开关（会标记方块实体需要保存）。 */
    void rscc$setStorageEnabled(boolean enabled);

    /**
     * 该自动合成仓当前接入的 RS 网络（未接入返回 null）。
     * <p>关闭内部存储前要用它把仓内内容写回网络（{@code /rs_create_compat autocrafter storage off}），
     * 拿不到网络时一律拒绝关闭，保证「绝不销毁 / 绝不困住」玩家资源。</p>
     */
    @org.jetbrains.annotations.Nullable
    com.refinedmods.refinedstorage.api.network.Network rscc$getNetwork();

    /**
     * 内部输出物品存储（用于 capability 暴露与关闭时回流）。
     * <p>种类无上限，总容量 = 配置槽位数 × 64；仍是 {@code ItemStackHandler} 的子类。
     */
    RsccUnboundedItemStorage rscc$getOutputStorage();

    /**
     * 内部输出流体存储（用于 capability 暴露与关闭时回流）。
     * <p>种类无上限，总容量 = 配置的流体容量；仍是 {@code FluidTank} 的子类，
     * 因此能力注册处（{@code Capabilities.FluidHandler.BLOCK}）无需改动。
     */
    RsccUnboundedFluidStorage rscc$getOutputTank();
}
