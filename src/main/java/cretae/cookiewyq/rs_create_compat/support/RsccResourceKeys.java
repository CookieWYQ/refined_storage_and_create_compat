package cretae.cookiewyq.rs_create_compat.support;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.common.support.resource.ResourceCodecs;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import org.jetbrains.annotations.Nullable;

/**
 * 资源键的<b>通用</b>工具：本模组只认 RS 的 {@link ResourceKey} / {@link PlatformResourceKey}，
 * <b>绝不</b>自己实现任何第三方资源类型（气体 / 化学品等），也<b>绝不</b> import 任何联动模组的类。
 *
 * <h2>为什么需要「令牌」</h2>
 * <p>接收器 / 请求器的界面在历史上只用两个字段描述资源：{@code 注册名 + 是否流体}。
 * 这套两态映射对任意 {@code PlatformResourceKey} 不成立（例如官方 Mekanism 联动的
 * {@code ChemicalResource} 不在原版物品 / 流体注册表里）。为了让<b>没有改过界面的旧路径</b>
 * 也能往返一条第三方资源，这里把完整的键用 RS <b>自己的</b>
 * {@link ResourceCodecs#CODEC} 编成一段稳定的 JSON 文本，再加前缀当作「注册名」传递。
 *
 * <p><b>依赖与边界</b>：</p>
 * <ul>
 *     <li>{@link ResourceCodecs#CODEC} 是按 RS 的资源类型注册表 dispatch 的 —— 装了官方联动后，
 *     联动自己会往那个注册表里登记气体类型，因此这里的编解码<b>自动</b>认得气体；
 *     没装时那段 JSON 根本不会被产生（网络里也没有气体资源）；</li>
 *     <li>本类<b>不</b>引用 {@code mekanism} 的任何类型，纯反射级（其实是 codec 级）通用处理；</li>
 *     <li>物品 / 流体<b>永远</b>走原来的「注册名 + 是否流体」，令牌只服务第三方类型
 *     （{@link #token} 对物品 / 流体返回 {@code null}），所以旧存档与旧界面逐字不变。</li>
 * </ul>
 */
public final class RsccResourceKeys {
    /**
     * 第三方类型令牌的前缀。
     * <p>前缀之后是一段 JSON，里面必然出现 {@code {}"} 这些<b>不是合法 ResourceLocation 路径</b>的字符，
     * 所以旧界面里的 {@code ResourceLocation.tryParse(...)} 会直接失败、原样显示令牌，
     * 而不会把它误认成某个注册名去查物品 / 流体注册表。</p>
     */
    public static final String TOKEN_PREFIX = "rscc-key:";

    private RsccResourceKeys() {
    }

    /**
     * 物品 / 流体：平台对这两类有<b>专用容器能力</b>（{@code IItemHandler} / {@code IFluidHandler}）
     * 与既有缓存，因此保留原来的写入路径。
     *
     * <p>这是「平台能力分派」而不是「按注册名映射两态」：判据是键的<b>实现类型</b>，
     * 任何第三方键（气体等）都会走到通用分支，不存在「未识别 ⇒ 拒绝」。</p>
     */
    public static boolean isItemOrFluid(@Nullable final ResourceKey resource) {
        return resource instanceof ItemResource || resource instanceof FluidResource;
    }

    /**
     * 把第三方资源键编成「可放进旧界面字符串字段」的稳定令牌；物品 / 流体或编码失败返回 {@code null}。
     *
     * @param registries 服务端的注册表上下文（数据组件解析需要它）；{@code null} 时退化为纯 JSON
     */
    @Nullable
    public static String token(@Nullable final PlatformResourceKey key,
                               @Nullable final HolderLookup.Provider registries) {
        if (key == null || isItemOrFluid(key)) {
            return null; // 物品 / 流体继续用注册名，令牌只服务第三方类型
        }
        try {
            final DynamicOps<JsonElement> ops = ops(registries);
            return ResourceCodecs.CODEC.encodeStart(ops, key).result()
                .map(json -> TOKEN_PREFIX + json.toString())
                .orElse(null);
        } catch (RuntimeException exception) {
            // 第三方类型的 codec 抛异常 ⇒ 这条资源无法往返 ⇒ 调用方把它排除在选择列表外（绝不塞假 id）
            return null;
        }
    }

    /** 令牌 → 第三方资源键；不是令牌 / 解码失败返回 {@code null}（调用方据此判「资源非法」）。 */
    @Nullable
    public static PlatformResourceKey parseToken(@Nullable final String token,
                                                 @Nullable final HolderLookup.Provider registries) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            return null;
        }
        try {
            final DynamicOps<JsonElement> ops = ops(registries);
            return ResourceCodecs.CODEC
                .parse(ops, JsonParser.parseString(token.substring(TOKEN_PREFIX.length())))
                .result()
                .orElse(null);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** 编解码用的 ops：有注册表上下文就带上（与请求器订单 NBT 的 {@code ops(...)} 同一写法）。 */
    private static DynamicOps<JsonElement> ops(@Nullable final HolderLookup.Provider registries) {
        return registries == null
            ? JsonOps.INSTANCE
            : RegistryOps.create(JsonOps.INSTANCE, registries);
    }
}
