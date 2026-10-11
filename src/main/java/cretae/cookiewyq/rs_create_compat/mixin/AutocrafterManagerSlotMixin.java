package cretae.cookiewyq.rs_create_compat.mixin;

import com.refinedmods.refinedstorage.common.autocrafting.PatternSlot;
import com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager.AutocrafterManagerContainerMenu;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 合成仓管理器（含经远程终端反射打开的实例）的服务端样板槽补上 mayPlace 校验：
 * RS 原版 {@code AutocrafterManagerContainerMenu.addServerSideSlots} 给每个自动合成仓的
 * 样板容器挂的是<b>普通 Slot（mayPlace 恒为 true）</b>，只靠客户端 PatternSlot 拦截。
 * 这会导致 shift 点击/快速放置等服务端驱动路径绕过客户端校验，把非样板物品（升级件、
 * 构件等）塞进真实自动合成仓的样板槽、顶掉原样板。
 * <p>
 * 这里把服务端槽改成跟随容器 {@link Container#canPlaceItem(int, ItemStack)} —— 自动合成仓
 * 样板容器只放行真正的样板，因此任何入口（点击、shift、管道驱动菜单逻辑）都无法放入非样板，
 * 与“原版非样板不能放入”行为一致。
 * <p>
 * {@code addServerSideSlots} 在 RS 里有 <b>两个同名的私有重载</b>：
 * {@code addServerSideSlots(List<Group>)}（只是遍历）与真正建槽的那个重载。Mixin 的
 * {@code method} 只写方法名时会把<b>两个重载全部作为目标</b>，因此这里显式写出精确描述符，
 * 锁定到真正建槽的那一个（描述符里的嵌套类用 JVM 内部名 {@code $Group}）。
 * <p>
 * <b>RS 2.0.9 的签名变化</b>：2.0.0 里建槽重载是 {@code addServerSideSlots(Group)} 且用的是
 * 普通 {@code new Slot(...)}；2.0.9 给它加了 {@code Level} 形参，变成
 * {@code addServerSideSlots(Group, Level)}，并且改用 RS 自己的
 * {@code PatternSlot(Container,int,int,int,Level)}（= ValidatedSlot，mayPlace 走
 * {@code PatternProviderItem.isValid(stack, level)}）。因此这里的描述符必须补上
 * {@code Level}，Redirect 处理函数也要多接一个 {@code Level} 形参（顺序须与字节码压栈一致，
 * 即原 4 个参数之后），@At 目标则由 {@code NEW Slot} 改指向 {@code NEW PatternSlot}。
 * 用 {@code container.canPlaceItem(...)} 收口与 2.0.9 的 {@code isPresent(getPattern(...))}
 * 判据等价，故槽位语义不变。
 * <p>
 * <b>2.0.9 启动即崩的根因（2026-10-11，专用服务端）</b>：上一次改动只把 {@code @At} 的 NEW 目标
 * 从 {@code Slot} 换成了 {@code PatternSlot}，<b>却没有同步改 handler 的返回类型</b>。
 * Mixin 对「NEW 工厂模式」的硬规则是：
 * <ul>
 *   <li>handler 的<b>返回类型必须精确等于被构造的类型</b>（{@code PatternSlot}）；</li>
 *   <li>handler 的形参 = <b>被重定向构造器的形参</b>（顺序一致），
 *       其后可选地追加「目标方法自身形参」的前缀；</li>
 * </ul>
 * 于是运行期抛 {@code InvalidInjectionException: @Redirect factory method ... has an invalid
 * signature. Found unexpected return type net.minecraft.world.inventory.Slot, expected
 * com.refinedmods.refinedstorage.common.autocrafting.PatternSlot} —— 模组加载阶段即崩，
 * 服务端根本起不来。修法：返回类型与 {@code new} 出来的类型严格一致（下面 {@link PatternSlot}），
 * 方法体自然也必须是 {@code new PatternSlot(...)}（匿名子类仍是 PatternSlot，
 * 描述符层面完全一致）。{@code javap} 证据（RS 2.0.9 真实字节码）：
 * <pre>
 *   addServerSideSlots(Group, Level):
 *     52: new  #207  // class com/refinedmods/refinedstorage/common/autocrafting/PatternSlot
 *     57: aload 5    // container
 *     59: iload 6    // index
 *     61: iconst_0   // x
 *     62: iconst_0   // y
 *     63: aload_2    // level
 *     64: invokespecial PatternSlot."&lt;init&gt;":(Lnet/minecraft/world/Container;IIILnet/minecraft/world/level/Level;)V
 * </pre>
 * 即构造器形参顺序 = (Container, int, int, int, Level)，与下面 handler 的形参逐个对应。
 * 同类坑（返回类型与 NEW 目标不一致）已由 {@code tools/verify_mixin_shadows.py} 静态拦截。
 */
@Mixin(AutocrafterManagerContainerMenu.class)
public abstract class AutocrafterManagerSlotMixin {

    @Redirect(
        method = "addServerSideSlots("
            + "Lcom/refinedmods/refinedstorage/common/autocrafting/autocraftermanager/"
            + "AutocrafterManagerBlockEntity$Group;"
            + "Lnet/minecraft/world/level/Level;)V",
        at = @At(value = "NEW",
                 target = "Lcom/refinedmods/refinedstorage/common/autocrafting/PatternSlot;"),
        expect = 1,
        require = 0
    )
    // 【返回类型】必须是 @At 的 NEW 目标类型本身（PatternSlot），否则 Mixin 在加载期直接抛
    // InvalidInjectionException 崩启动（1.1.0 实机崩溃即此）；这里返回匿名子类实例，
    // 对 Mixin 而言 handler 描述符仍是 (...)->Lcom/.../PatternSlot;，与目标严格一致。
    // 【形参】= 真实字节码里 PatternSlot ctor 的形参，顺序 (Container, int, int, int, Level)，
    // 见类注释里的 javap 反汇编；此处不再追加「目标方法形参」，因此不接 (Group, Level)。
    private static PatternSlot rscc$patternOnlyManagerServerSlot(final Container container, final int index,
                                                                  final int x, final int y, final Level level) {
        return new PatternSlot(container, index, x, y, level) {
            @Override
            public boolean mayPlace(final ItemStack stack) {
                return stack.isEmpty() || container.canPlaceItem(this.index, stack);
            }
        };
    }
}
