package cretae.cookiewyq.rs_create_compat.support;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;

import java.util.Map;

/**
 * 降解表的数据包加载器：扫描 {@code data/<namespace>/decomposition/*.json} 并喂给
 * {@link DecompositionTable#apply}。
 *
 * <h2>为什么要显式继承 {@link SimpleJsonResourceReloadListener}</h2>
 * <p>它已经实现了「prepare 阶段异步遍历资源包 + apply 阶段回主线程」的整套流程，
 * 并且原生支持 {@code ResourceLocation → JsonElement} 的目录扫描；自己再写一遍只会多一处与
 * 数据包版本相关的手工逻辑（历史教训：手写目录遍历漏掉 {@code resources} 里的 pack 过滤规则）。</p>
 *
 * <h2>注册方式</h2>
 * <p>{@link AddReloadListenerEvent} 触发在 <b>NeoForge.EVENT_BUS（game bus）</b>
 * （neoforge-21.1.248 的事件类注释原文：{@code The event is fired on the NeoForge#EVENT_BUS}），
 * 因此用 {@code @EventBusSubscriber(modid = ...)}（默认 game bus）静态方法自注册即可，
 * <b>主类不需要额外一行</b>。</p>
 *
 * <h2>容错</h2>
 * <p>本类只负责「把 JSON 收上来」；<b>所有</b>解析容错都在 {@link DecompositionTable#apply}
 * 里逐条 try/catch（不存在 item / 越界数值 / 坏文件都只跳过自己）。因此别人的数据包写错一个 id
 * 不会让整个服务端重载失败 —— 这正是本轮硬要求之一。</p>
 */
@EventBusSubscriber(modid = RS_Create_Compat.MODID)
public final class DecompositionDataLoader extends SimpleJsonResourceReloadListener {
    /** 数据包目录名（固定：{@code data/<ns>/decomposition/*.json}）。 */
    public static final String DIRECTORY = "decomposition";

    /** 宽松 Gson：允许 NaN / 尾逗号等手写 JSON 常见笔误，尽量不因为格式小事整份丢掉。 */
    private static final Gson GSON = new GsonBuilder().setLenient().create();
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 唯一实例（重载监听器无状态）。 */
    private static final DecompositionDataLoader INSTANCE = new DecompositionDataLoader();

    private DecompositionDataLoader() {
        super(GSON, DIRECTORY);
    }

    /** 把本监听器挂进「服务端数据重载」流程（每次 {@code /reload} 与开服都会跑一遍）。 */
    @SubscribeEvent
    public static void onAddReloadListeners(final AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }

    @Override
    protected void apply(final Map<ResourceLocation, JsonElement> files,
                         final ResourceManager resourceManager,
                         final ProfilerFiller profiler) {
        try {
            DecompositionTable.apply(files, GSON);
        } catch (final RuntimeException exception) {
            // 兜底：apply 内部已经逐条容错，这里再包一层是「绝不让重载因为本模组而中断」的最后一道闸。
            // 保留旧表不动（宁可用上一份数据，也不要变出一张空表把全服机器停掉）。
            LOGGER.error("[rs_create_compat] 降解表重载失败，保留上一份数据", exception);
        }
    }
}
