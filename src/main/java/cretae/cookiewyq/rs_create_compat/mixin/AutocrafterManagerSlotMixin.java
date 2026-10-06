package cretae.cookiewyq.rs_create_compat.mixin;

import com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager.AutocrafterManagerContainerMenu;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
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
 * {@code addServerSideSlots(List<Group>)}（只是遍历，不含 new Slot）与
 * {@code addServerSideSlots(Group)}（真正 new Slot 的那个）。Mixin 的 {@code method}
 * 只写方法名时会把<b>两个重载全部作为目标</b>，因此这里显式写出精确描述符，锁定到
 * 真正 new Slot 的那个重载（描述符里的嵌套类用 JVM 内部名 {@code $Group}）。
 */
@Mixin(AutocrafterManagerContainerMenu.class)
public abstract class AutocrafterManagerSlotMixin {

    @Redirect(
        method = "addServerSideSlots("
            + "Lcom/refinedmods/refinedstorage/common/autocrafting/autocraftermanager/"
            + "AutocrafterManagerBlockEntity$Group;)V",
        at = @At(value = "NEW", target = "Lnet/minecraft/world/inventory/Slot;"),
        expect = 1,
        require = 0
    )
    private static Slot rscc$patternOnlyManagerServerSlot(final Container container, final int index,
                                                          final int x, final int y) {
        return new Slot(container, index, x, y) {
            @Override
            public boolean mayPlace(final ItemStack stack) {
                return stack.isEmpty() || container.canPlaceItem(this.index, stack);
            }
        };
    }
}
