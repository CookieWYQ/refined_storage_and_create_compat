package cretae.cookiewyq.rs_create_compat.compat.jade;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

/**
 * <b>Jade 的「服务端 → 客户端」数据通道：序列装配总线的状态</b>（用户第 8 项 / 任务 A）。
 *
 * <h2>为什么必须走这条通道（而不是客户端自己读）</h2>
 * <p>「与哪台执行仓绑定」「负责哪些类别」「输入总线是否全自动」这三份数据的权威都在
 * <b>服务端方块实体</b>里：客户端的方块实体镜像<b>一律返回空</b>
 * （{@code RsccImporterExecutorMode#rscc$getLinkedExecutor()} 等接口在客户端恒为
 * {@code null} / {@code List.of()}，见那里「客户端状态一律以 S2C 同步为准」的说明）。
 * 既有的界面同步链路走的是「容器菜单」——只有玩家<b>打开界面</b>时才存在，
 * 而瞄准提示恰恰是<b>不打开界面</b>时看的，因此那条路帮不上忙。</p>
 *
 * <p>Jade 官方给这种需求留的就是本接口：客户端发一次「我在看这一格」，
 * 服务端把这一格的 NBT 回给**那个**玩家（目标校验、节流、只发一次都由 Jade 负责），
 * 客户端组件再用 {@link BlockAccessor#getServerData()} 读它。
 * <b>因此不需要本模组新增任何同步包</b>（{@code network/**} 一个字节都不用动，
 * {@code check_payload_registration.py} 的清单也不受影响）。</p>
 *
 * <h2>为什么注册给「输入总线 / 输出总线这两个方块类」而不是所有方块</h2>
 * <p>服务端数据 provider 每被请求一次就要跑一遍组装（{@code appendServerData}）。
 * 注册给 {@code Block.class} 会让玩家看任意方块都白跑一次；注册给两个具体类则
 * <b>只有真的在看总线时才跑</b>，且 Jade 的层级查找会自动覆盖这两个类的全部染色变体。</p>
 *
 * <h2>为什么本类不能引用客户端类型（专用服务端安全性）</h2>
 * <p>{@code IWailaPlugin#register(IWailaCommonRegistration)}（注册本 provider 的地方）
 * 在<b>专用服务端</b>也会被 Jade 调用，因此从那里可达的类里出现任何
 * {@code net.minecraft.client.*} / {@code GuiGraphics} 一类类型都可能让专服在
 * 「注册期」就去加载客户端类而崩（本工程刚修过一次这种崩溃，见
 * {@code tools/selfcheck_round47_dedicated_server_safety.py}）。本类与
 * {@link RsccBusJadePayload} 因此<b>只</b>使用公共类型：NBT、注册表、{@code Component}。</p>
 */
public final class RsccBusJadeServerData implements IServerDataProvider<BlockAccessor> {
    /**
     * 本 provider 的唯一 id（Jade 用它去重 / 建配置项）。
     * <p><b>与客户端组件的 id 刻意分开</b>：两者是不同的 provider（一个跑在服务端、
     * 一个跑在客户端），共用 id 会让 Jade 的去重表把它们当成同一个。</p>
     */
    private static final ResourceLocation UID =
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "bus_data");

    /** 服务端组装：把总线的三份状态写进只发给「正在看这一格」的那个玩家的 NBT。 */
    @Override
    public void appendServerData(final CompoundTag tag, final BlockAccessor accessor) {
        RsccBusJadePayload.write(tag, accessor.getLevel(), accessor.getPosition(), accessor.getBlockEntity());
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }
}
