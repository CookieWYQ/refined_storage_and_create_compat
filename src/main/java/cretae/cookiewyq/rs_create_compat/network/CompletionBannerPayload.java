package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：一条“完成横幅”数据（仿 RS 自动合成完成 Toast 的多行报告横幅）。
 * <p>由服务器在任务完成时构造并广播给附近玩家；客户端收到后弹出一条右下角 Toast，
 * 其中包含若干行文本，每行可有任意数量的“图标 + 文本”段 —— 用于标注使用了多少物品、
 * 实得多少目标产物与产率，图标随文字逐段内联渲染。
 */
public record CompletionBannerPayload(List<CompletionBannerPayload.Row> rows) implements CustomPacketPayload {
    public static final Type<CompletionBannerPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "completion_banner"));

    /** 一行报告：color 为整行文字基色（ARGB），icons/texts 等长对齐；icons 中的空栈表示该段无图标。
     *  <p>{@code colors} 为<b>逐段颜色</b>（与 icons/texts 等长）：给「一行里用颜色区分不同原料」用。
     *  传空表 = 全行都用 {@code color}（既有调用方零改动）。</p> */
    public record Row(int color, List<ItemStack> icons, List<String> texts, List<Integer> colors) {
        public Row {
            icons = List.copyOf(icons);
            texts = List.copyOf(texts);
            final int size = Math.min(icons.size(), texts.size());
            if (colors == null || colors.isEmpty()) {
                colors = java.util.Collections.nCopies(size, color);
            } else {
                final List<Integer> normalized = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    normalized.add(i < colors.size() && colors.get(i) != null ? colors.get(i) : color);
                }
                colors = List.copyOf(normalized);
            }
        }

        public static Row text(final int color, final String text) {
            return new Row(color, List.of(ItemStack.EMPTY), List.of(text), List.of());
        }

        public static Row withIcon(final int color, final ItemStack icon, final String text) {
            return new Row(color, List.of(icon == null ? ItemStack.EMPTY : icon), List.of(text), List.of());
        }

        public static Row segments(final int color, final List<ItemStack> icons, final List<String> texts) {
            return new Row(color, icons, texts, List.of());
        }

        /** 逐段颜色版：一行内多个「图标 + 文本」段可以各自不同颜色（用于区分不同原料）。 */
        public static Row colored(final List<ItemStack> icons, final List<String> texts,
                                  final List<Integer> colors) {
            return new Row(colors.isEmpty() ? 0xFFFFFFFF : colors.get(0), icons, texts, colors);
        }
    }

    /** 「原文是语言键」的段标记：客户端看到文本以它开头时，按语言键 + 参数解析（见 {@link #localized}）。 */
    public static final char LANG_MARKER = '\u0001';
    /** 语言键与参数 / 参数之间的分隔符。 */
    public static final char LANG_ARG_SEP = '\u0002';

    /**
     * 构造「客户端按语言键解析」的段文本（{@code \u0001键\u0002参数1\u0002参数2}）。
     * <p><b>为什么不在服务端解析</b>：横幅文本走 {@code String} 传输并在客户端渲染，服务端的
     * {@code Language} 在专服上只有 en_us；把语言键原样送过去由客户端解析，才能让每位玩家看到自己的语言。</p>
     */
    public static String localized(final String key, final Object... args) {
        final StringBuilder builder = new StringBuilder(key.length() + 16).append(LANG_MARKER).append(key);
        if (args != null) {
            for (final Object arg : args) {
                builder.append(LANG_ARG_SEP).append(arg == null ? "" : arg);
            }
        }
        return builder.toString();
    }


    public static final StreamCodec<RegistryFriendlyByteBuf, CompletionBannerPayload> STREAM_CODEC =
        new StreamCodec<>() {
            @Override
            public void encode(final RegistryFriendlyByteBuf buf, final CompletionBannerPayload payload) {
                buf.writeVarInt(payload.rows().size());
                for (final Row row : payload.rows()) {
                    buf.writeVarInt(row.color());
                    final int segs = Math.min(row.icons().size(), row.texts().size());
                    buf.writeVarInt(segs);
                    for (int i = 0; i < segs; i++) {
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, row.icons().get(i));
                        buf.writeUtf(row.texts().get(i));
                    }
                    // 逐段颜色（与段数等长；保证「一行内用颜色区分不同原料」能原样送达客户端）
                    for (int i = 0; i < segs; i++) {
                        buf.writeVarInt(row.colors().get(i));
                    }
                }
            }

            @Override
            public CompletionBannerPayload decode(final RegistryFriendlyByteBuf buf) {
                final int rowCount = buf.readVarInt();
                final List<Row> rows = new ArrayList<>(rowCount);
                for (int r = 0; r < rowCount; r++) {
                    final int color = buf.readVarInt();
                    final int segs = buf.readVarInt();
                    final List<ItemStack> icons = new ArrayList<>(segs);
                    final List<String> texts = new ArrayList<>(segs);
                    for (int i = 0; i < segs; i++) {
                        icons.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
                        texts.add(buf.readUtf());
                    }
                    final List<Integer> colors = new ArrayList<>(segs);
                    for (int i = 0; i < segs; i++) {
                        colors.add(buf.readVarInt());
                    }
                    rows.add(new Row(color, icons, texts, colors));
                }
                return new CompletionBannerPayload(rows);
            }
        };

    /**
     * S2C 处理器：<b>只负责「投递到客户端主线程 + 交给客户端实现」</b>，本类因此不含任何客户端类型。
     *
     * <p><b>为什么不能像以前那样把 Toast 直接弹在这里</b>：本类在<b>注册期</b>就被专用服务端加载
     * 并链接（注册表达式读了本类的 {@code TYPE} 静态字段），而
     * {@code addToast(new CompatCompletionToast(...))} 这种「把客户端类交给客户端形参」的写法会让
     * 链接期校验器做跨类可赋值性检查 ⇒ 被迫加载 {@code ...toasts.Toast}（服务端没有这个类）⇒
     * {@code NoClassDefFoundError}，模组装到专服上启动即崩。真正的弹出逻辑见
     * {@code client/ClientPayloadSink#showCompletionBanner}，桥接层见 {@link ClientPayloadHooks}。</p>
     */
    public static void handle(final CompletionBannerPayload payload,
                              final net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadHooks.get().showCompletionBanner(payload));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
