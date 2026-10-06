package cretae.cookiewyq.rs_create_compat.mixin.accessor;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 访问器：暴露原版 {@link Screen} 自身声明的受保护成员，供 Mixin 在不触碰继承成员的前提下使用。
 * <p><b>为什么必须挂在「声明类」上</b>：{@code addRenderableWidget} 声明在
 * {@code net.minecraft.client.gui.screens.Screen} <b>自身</b>，而 RS 的
 * {@code com.refinedmods.refinedstorage.common.exporter.ExporterScreen} 是它的第四层子类
 * （{@code ExporterScreen → AbstractFilterScreen → AbstractBaseScreen → AbstractContainerScreen
 * → Screen}）。若改在 {@code ExporterScreen} 上写 {@code @Shadow} 属于「shadow 继承成员」，
 * 会在<b>客户端打开界面时</b>抛
 * {@code InvalidMixinException: ... was not located in the target class}。
 * <p>本接口挂在 {@link Screen} 上，按「名字 + 描述符」在 {@code Screen} 自身查找，必然命中。
 */
@Mixin(Screen.class)
public interface ScreenAccessor {
    /**
     * 把控件挂进 {@link Screen} 的 widgets 列表（等价于 {@code addRenderableWidget}：
     * {@code renderables.add(widget) + children.add(widget)}）。
     * <p>{@code addRenderableWidget} 是 {@code protected}，Mixin 类并非 {@link Screen} 的子类，
     * 无法直接调用；故用 {@code @Invoker} 让 Mixin 在 {@link Screen} 内生成调用。
     * 形参 / 返回类型直接按<b>泛型擦除后的描述符</b>书写（{@code <T extends GuiEventListener & Renderable
     * & NarratableEntry> T} 擦除为上界 {@link GuiEventListener}），与目标方法完全一致；
     * 传进去的控件一定同时实现 {@link Renderable} / {@link NarratableEntry}，运行时不会有类型问题。
     */
    @Invoker("addRenderableWidget")
    GuiEventListener rscc$addRenderableWidget(GuiEventListener widget);
}
