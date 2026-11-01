package cretae.cookiewyq.rs_create_compat.mixin.client;

import com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager.AutocrafterManagerData;
import cretae.cookiewyq.rs_create_compat.support.RsccClientText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * RS「自动合成仓管理器」分组名的<b>客户端解析点</b>。
 *
 * <p><b>为什么必须在这里解析</b>（本地化 bug 的完整链）：</p>
 * <ol>
 *     <li>RS {@code AutocrafterManagerBlockEntity#getGroups()} 在<b>服务端</b>执行
 *     {@code Collectors.groupingBy(a -> a.getAutocrafterName().getString())}
 *     —— 名字在这里就已经被压成 {@code String}；</li>
 *     <li>该字符串经 {@code AutocrafterManagerData.Group#name}（{@code ByteBufCodecs.STRING_UTF8}）
 *     原样发给客户端；</li>
 *     <li>客户端 {@code AutocrafterManagerContainerMenu.ViewGroup.from} 取 {@code group.name()}，
 *     再由 {@code AutocrafterManagerScreen#renderGroups} 直接 {@code drawString} 画出来。</li>
 * </ol>
 * <p>也就是说：<b>客户端从头到尾没有机会翻译</b>，而服务端（专用服务端）的语言表只有 {@code en_us}
 * ⇒ 任何「可翻译组件」经此链路都必然显示英文。</p>
 *
 * <p>本 Mixin 因此挂在<b>数据记录</b>的访问器上（而不是绘制处）：{@code Group#name()} 是客户端
 * 构造视图分组的唯一来源，在这里换成玩家语言的文本后，
 * <b>绘制与「按自动合成仓名搜索」两处同时生效</b>（搜索用的是同一个 {@code ViewGroup.name}）。
 * 只处理带语言键标记的字符串（本模组自己送的），RS 原版的名字、玩家自定义名一律原样返回。</p>
 *
 * <p><b>只挂在 client 段</b>（见 {@code rs_create_compat.mixins.json}）：专用服务端根本不加载它，
 * 因此服务端编码时仍是原始数据，绝不会把「服务端所在机器的语言」写进协议。</p>
 */
@Mixin(AutocrafterManagerData.Group.class)
public abstract class AutocrafterManagerGroupNameMixin {
    /** 客户端解析：语言键 → 玩家语言文本；其它字符串原样返回。 */
    @Inject(method = "name", at = @At("RETURN"), cancellable = true)
    private void rscc$localizeGroupName(final CallbackInfoReturnable<String> cir) {
        final String raw = cir.getReturnValue();
        if (RsccClientText.isKeyText(raw)) {
            cir.setReturnValue(RsccClientText.resolveText(raw));
        }
    }
}
