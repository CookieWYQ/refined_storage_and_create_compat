package cretae.cookiewyq.rs_create_compat.network;

import cretae.cookiewyq.rs_create_compat.RS_Create_Compat;
import cretae.cookiewyq.rs_create_compat.menu.SequencePatternTerminalMenu;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * C2S：JEI "+" 配方转移 —— 把 Create 序列装配配方填入序列装配终端
 * （原料槽 + 流程编排单元样板 + 产物 / 废料 + 循环次数）。
 * <p>machines/stepInputs/stepFluids 是<b>展开后的一步一行</b>（每个元素 = 配方序列里的一级）；
 * 「相邻且完全相同」的步骤合并成一列 + 重复次数 N 这件事由<b>服务端</b>在
 * {@code SequencePatternTerminalBlockEntity#applyRecipeToArrangement} 里统一完成
 * （服务端唯一权威，客户端不做合并，避免两端记的 N 分叉）。</p>
 * <p><b>产物 vs 废料</b>：Create 的 {@code resultPool} 里，必定产出的那一项（概率 100%）算
 * <b>产物</b>，带概率的副产物算<b>废料</b>；两边的物品数量都取 {@code ItemStack#getCount()}
 * （即「产出数量」），概率取 {@code ProcessingOutput#getChance()}（0..100 百分比，逐项对应）。</p>
 *
 * <p><b>每步的「输入原料组候选」</b>（{@link #stepCandidates}，与 {@link #stepInputs} 逐下标平行）：
 * 标签型 ingredient（如列车轨道机械手步的「铁粒 <b>或</b> 锌粒」）的整组候选。
 * 客户端在 JEI 转移时按配方数据算好（那里拿得到 {@code Ingredient#getItems()} 的全部候选），
 * 服务端只负责落盘 —— 因此不需要在服务端重新解析配方，两端口径天然一致。</p>
 */
public record SetSequenceImportPacket(int containerId,
                                      ResourceLocation recipeId,
                                      List<String> machines,
                                      List<ItemStack> stepInputs,
                                      List<List<ItemStack>> stepCandidates,
                                      List<net.neoforged.neoforge.fluids.FluidStack> stepFluids,
                                      List<Integer> stepCounts,
                                      int loops,
                                      ItemStack ingredient,
                                      /** 起步原料的全部候选（「任意台阶」一类；空 = 只有代表物一件）。2026-10-05 新增。 */
                                      List<ItemStack> ingredientCandidates,
                                      List<ItemStack> results,
                                      List<Integer> resultChances,
                                      List<ItemStack> scraps,
                                      List<Integer> scrapChances) implements CustomPacketPayload {
    public static final Type<SetSequenceImportPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_sequence_import"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetSequenceImportPacket> STREAM_CODEC =
        StreamCodec.of(SetSequenceImportPacket::encode, SetSequenceImportPacket::decode);

    private static void encode(final RegistryFriendlyByteBuf buf, final SetSequenceImportPacket packet) {
        buf.writeVarInt(packet.containerId());
        // 配方 id（供服务端把该步配方写进单元样板；客户端拿不到配方时传空串）
        buf.writeUtf(packet.recipeId() == null ? "" : packet.recipeId().toString());
        buf.writeVarInt(packet.machines().size());
        for (final String machine : packet.machines()) {
            buf.writeUtf(machine);
        }
        buf.writeVarInt(packet.stepInputs().size());
        for (final ItemStack stack : packet.stepInputs()) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
        }
        // 每步的「输入原料组候选」（逐下标与 stepInputs 平行；空表 = 只有代表物一件）
        final List<List<ItemStack>> candidates =
            packet.stepCandidates() == null ? List.of() : packet.stepCandidates();
        buf.writeVarInt(candidates.size());
        for (final List<ItemStack> group : candidates) {
            writeStacks(buf, group);
        }
        // 每步的输入流体（导入即自动标注；无则空栈）
        final List<net.neoforged.neoforge.fluids.FluidStack> fluids =
            packet.stepFluids() == null ? List.of() : packet.stepFluids();
        buf.writeVarInt(fluids.size());
        for (final net.neoforged.neoforge.fluids.FluidStack fluid : fluids) {
            net.neoforged.neoforge.fluids.FluidStack.OPTIONAL_STREAM_CODEC.encode(buf, fluid);
        }
        buf.writeVarInt(packet.stepCounts().size());
        for (final int count : packet.stepCounts()) {
            buf.writeVarInt(count);
        }
        buf.writeVarInt(packet.loops());
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, packet.ingredient());
        // 起步原料的全部候选（「任意台阶」；空表 = 只有代表物一件，与老行为一致）
        writeStacks(buf, packet.ingredientCandidates());
        writeOutputs(buf, packet.results(), packet.resultChances());
        writeOutputs(buf, packet.scraps(), packet.scrapChances());
    }

    private static void writeOutputs(final RegistryFriendlyByteBuf buf, final List<ItemStack> stacks,
                                     final List<Integer> chances) {
        final List<ItemStack> safeStacks = stacks == null ? List.of() : stacks;
        final List<Integer> safeChances = chances == null ? List.of() : chances;
        buf.writeVarInt(safeStacks.size());
        for (final ItemStack stack : safeStacks) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
        }
        buf.writeVarInt(safeChances.size());
        for (final int chance : safeChances) {
            buf.writeVarInt(chance);
        }
    }

    private static SetSequenceImportPacket decode(final RegistryFriendlyByteBuf buf) {
        final int containerId = buf.readVarInt();
        final ResourceLocation recipeId = ResourceLocation.tryParse(buf.readUtf());
        final List<String> machines = buf.readList(b -> b.readUtf());
        final List<ItemStack> stepInputs = new java.util.ArrayList<>();
        final int stepInputCount = buf.readVarInt();
        for (int i = 0; i < stepInputCount; i++) {
            stepInputs.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
        }
        // 每步的「输入原料组候选」（与 stepInputs 逐下标平行；顺序必须与 encode 完全一致，否则整表串位）
        final List<List<ItemStack>> stepCandidates = new java.util.ArrayList<>();
        final int stepCandidateCount = buf.readVarInt();
        for (int i = 0; i < stepCandidateCount; i++) {
            stepCandidates.add(readStacks(buf));
        }
        final List<net.neoforged.neoforge.fluids.FluidStack> stepFluids = new java.util.ArrayList<>();
        final int stepFluidCount = buf.readVarInt();
        for (int i = 0; i < stepFluidCount; i++) {
            stepFluids.add(net.neoforged.neoforge.fluids.FluidStack.OPTIONAL_STREAM_CODEC.decode(buf));
        }
        final List<Integer> stepCounts = new java.util.ArrayList<>();
        final int stepCountSize = buf.readVarInt();
        for (int i = 0; i < stepCountSize; i++) {
            stepCounts.add(buf.readVarInt());
        }
        final int loops = buf.readVarInt();
        final ItemStack ingredient = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        // 起步原料候选（顺序必须与 encode 一致；漏读会整表串位）
        final List<ItemStack> ingredientCandidates = readStacks(buf);
        final List<ItemStack> results = readStacks(buf);
        final List<Integer> resultChances = readChances(buf);
        final List<ItemStack> scraps = readStacks(buf);
        final List<Integer> scrapChances = readChances(buf);
        return new SetSequenceImportPacket(containerId, recipeId, machines, stepInputs, stepCandidates,
            stepFluids, stepCounts, loops, ingredient, ingredientCandidates,
            results, resultChances, scraps, scrapChances);
    }

    private static List<ItemStack> readStacks(final RegistryFriendlyByteBuf buf) {
        final int count = buf.readVarInt();
        final List<ItemStack> stacks = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            stacks.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
        }
        return stacks;
    }

    private static void writeStacks(final RegistryFriendlyByteBuf buf, final List<ItemStack> stacks) {
        final List<ItemStack> safe = stacks == null ? List.of() : stacks;
        buf.writeVarInt(safe.size());
        for (final ItemStack stack : safe) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
        }
    }

    private static List<Integer> readChances(final RegistryFriendlyByteBuf buf) {
        final int count = buf.readVarInt();
        final List<Integer> chances = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            chances.add(buf.readVarInt());
        }
        return chances;
    }

    public static void handle(final SetSequenceImportPacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId() || !(menu instanceof SequencePatternTerminalMenu terminalMenu)) {
            return;
        }
        terminalMenu.importSequencedRecipe(packet.machines(), packet.stepInputs(), packet.stepCounts(),
            packet.loops(), packet.ingredient(), packet.results(), packet.resultChances(),
            packet.scraps(), packet.scrapChances(), packet.recipeId(), packet.stepFluids(),
            packet.stepCandidates(), packet.ingredientCandidates());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
