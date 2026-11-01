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
                                      List<Integer> scrapChances,
                                      /**
                                       * 解码阶段是否<b>因暂存上限丢弃了任何一条</b>（第 117 轮，复核 M2）。
                                       *
                                       * <p>这个分量<b>不上线格式</b> —— {@link #encode} 不写它、
                                       * {@link #decode} 在消费字节的过程中算出来（客户端构造时传 {@code false}）。
                                       * 它存在的理由：第 116 轮加的「每列表 4096 / 整包 8192」暂存上限一旦真的
                                       * 触发，各列表就<b>互相错位</b>（候选组比输入少、流体被清空），而
                                       * {@code budgetViolation} 与菜单都看不见这件事 —— 结果是「玩家收到
                                       * import.done，但导入进去的是半张配方」。那是静默失效，比拒绝糟得多。
                                       * 所以解码只要发现「丢过任何一条」，就在 {@code handle} 里<b>整包拒绝</b>并提示。</p>
                                       */
                                      boolean truncated) implements CustomPacketPayload {
    public static final Type<SetSequenceImportPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(RS_Create_Compat.MODID, "set_sequence_import"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetSequenceImportPacket> STREAM_CODEC =
        StreamCodec.of(SetSequenceImportPacket::encode, SetSequenceImportPacket::decode);

    /**
     * 单次导入最多接受的步数（第 115 轮，独立复核 H2）。
     * <p>解码时就把 {@code machines} 截到这里，服务端据此生成的单元样板数也随之有界；
     * 真正的业务上限在 {@code SequencePatternTerminalBlockEntity#MAX_IMPORT_STEPS}
     * （菜单侧还会再 clamp 一次，两处同值）。Create 的序列装配配方最多几十步，1024 只是「防爆」而非业务限制。</p>
     */
    private static final int MAX_IMPORT_STEPS = 1024;

    /**
     * 单次导入<b>每个列表</b>最多暂存多少条（第 115 轮，独立复核 H1）。
     * <p>{@code count} 是客户端给的 {@code VarInt}：以前 {@code new ArrayList<>(count)} 会在
     * <b>解码阶段</b>（menu 校验之前）按 {@code 2^31-1} 预分配 ⇒ OOME。现在照第 114 轮
     * {@code SetCollectionMarkerConfigPacket} 的口径：<b>仍按 count 消费字节</b>（保持流对齐），
     * 但只暂存前 N 条，且<b>不按客户端计数预分配</b>。</p>
     */
    private static final int MAX_IMPORT_LIST = 4096;

    /**
     * 单次导入<b>所有列表加起来</b>最多暂存多少条（第 116 轮）。
     *
     * <p><b>为什么单列表上限不够</b>：本包有 6 个列表（{@code stepInputs} / {@code stepCandidates}
     * 的每个内层组 / {@code stepFluids} / {@code ingredientCandidates} / {@code results} /
     * {@code scraps}）。第 115 轮只限制了<b>每个列表</b> 4096 条，于是「4096 个候选组、每组 4096 条」
     * 这种形状在解码阶段仍能一次造出上百万 {@code ItemStack} 对象（不只是体积问题，
     * 是解码阶段的堆尖峰）。这里再加一道<b>全局暂存计数</b>：对端字节照常消费（流必须对齐），
     * 但整包最多只留 {@value #MAX_IMPORT_TOTAL_ENTRIES} 条。</p>
     *
     * <p>真实 Create 序列配方：几十步 × 每组几十个候选 ≈ 数百条，远低于此值，<b>正常导入不受影响</b>。</p>
     */
    private static final int MAX_IMPORT_TOTAL_ENTRIES = 8192;

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
        // 第 116 轮：整包全局暂存计数（见 MAX_IMPORT_TOTAL_ENTRIES）。
        // 第 117 轮（复核 M2）：staged[0] = 已暂存条数，staged[1] = 「是否丢过任何一条」。
        // 只要有任何一个列表被上限截过，各列表之间就互相错位（候选组比输入少 / 流体被清空），
        // 而菜单与 budgetViolation 都看不见 ⇒ 必须让 handle 把整包拒掉，而不是静默导入半张配方。
        final int[] staged = new int[2];
        // 第 115 轮 H1：不再用 readList（它按客户端计数预分配），手工循环 + 有界暂存。
        final int machineCount = Math.max(0, buf.readVarInt());
        final List<String> machines = new java.util.ArrayList<>(Math.min(machineCount, 16));
        for (int i = 0; i < machineCount; i++) {
            final String machine = buf.readUtf();
            if (i < MAX_IMPORT_STEPS) {
                machines.add(machine);
            } else {
                staged[1] = 1;
            }
        }
        final List<ItemStack> stepInputs = new java.util.ArrayList<>();
        final int stepInputCount = Math.max(0, buf.readVarInt());
        for (int i = 0; i < stepInputCount; i++) {
            final ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            if (i < MAX_IMPORT_LIST && staged[0] < MAX_IMPORT_TOTAL_ENTRIES) {
                stepInputs.add(stack);
                staged[0]++;
            } else {
                staged[1] = 1;
            }
        }
        // 每步的「输入原料组候选」（与 stepInputs 逐下标平行；顺序必须与 encode 完全一致，否则整表串位）
        final List<List<ItemStack>> stepCandidates = new java.util.ArrayList<>();
        final int stepCandidateCount = Math.max(0, buf.readVarInt());
        for (int i = 0; i < stepCandidateCount; i++) {
            final List<ItemStack> group = readStacks(buf, staged);
            if (i < MAX_IMPORT_LIST) {
                stepCandidates.add(group);
            } else {
                staged[1] = 1;
            }
        }
        final List<net.neoforged.neoforge.fluids.FluidStack> stepFluids = new java.util.ArrayList<>();
        final int stepFluidCount = Math.max(0, buf.readVarInt());
        for (int i = 0; i < stepFluidCount; i++) {
            final net.neoforged.neoforge.fluids.FluidStack fluid =
                net.neoforged.neoforge.fluids.FluidStack.OPTIONAL_STREAM_CODEC.decode(buf);
            if (i < MAX_IMPORT_LIST && staged[0] < MAX_IMPORT_TOTAL_ENTRIES) {
                stepFluids.add(fluid);
                staged[0]++;
            } else {
                staged[1] = 1;
            }
        }
        final List<Integer> stepCounts = new java.util.ArrayList<>();
        final int stepCountSize = Math.max(0, buf.readVarInt());
        for (int i = 0; i < stepCountSize; i++) {
            final int count = buf.readVarInt();
            if (i < MAX_IMPORT_LIST) {
                stepCounts.add(count);
            } else {
                staged[1] = 1;
            }
        }
        final int loops = buf.readVarInt();
        final ItemStack ingredient = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        // 起步原料候选（顺序必须与 encode 一致；漏读会整表串位）
        final List<ItemStack> ingredientCandidates = readStacks(buf, staged);
        final List<ItemStack> results = readStacks(buf, staged);
        final List<Integer> resultChances = readChances(buf, staged);
        final List<ItemStack> scraps = readStacks(buf, staged);
        final List<Integer> scrapChances = readChances(buf, staged);
        return new SetSequenceImportPacket(containerId, recipeId, machines, stepInputs, stepCandidates,
            stepFluids, stepCounts, loops, ingredient, ingredientCandidates,
            results, resultChances, scraps, scrapChances, staged[1] != 0);
    }

    private static List<ItemStack> readStacks(final RegistryFriendlyByteBuf buf, final int[] staged) {
        final int count = Math.max(0, buf.readVarInt());
        // 第 115 轮 H1：**不**按客户端计数预分配（2^31-1 的 count 以前在这里直接 OOME）；
        // 第 116 轮再加「整包全局暂存上限」：仍按 count 逐个解码以保持流对齐，但整包最多留
        // MAX_IMPORT_TOTAL_ENTRIES 条（每个列表仍各受 MAX_IMPORT_LIST 约束）。
        // 第 117 轮：被截掉的条数不再「悄悄没了」—— staged[1] 置位，handle 会整包拒绝。
        final List<ItemStack> stacks = new java.util.ArrayList<>(Math.min(count, 64));
        for (int i = 0; i < count; i++) {
            final ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            if (i < MAX_IMPORT_LIST && staged[0] < MAX_IMPORT_TOTAL_ENTRIES) {
                stacks.add(stack);
                staged[0]++;
            } else {
                staged[1] = 1;
            }
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

    private static List<Integer> readChances(final RegistryFriendlyByteBuf buf, final int[] staged) {
        final int count = Math.max(0, buf.readVarInt());
        // 第 115 轮 H1：同 readStacks —— 不按客户端计数预分配。
        // 第 117 轮（复核 M2）：截断时置 staged[1]，由 handle 整包拒绝（概率列表被截 ⇒ 产物概率会错位）。
        final List<Integer> chances = new java.util.ArrayList<>(Math.min(count, MAX_IMPORT_LIST));
        for (int i = 0; i < count; i++) {
            final int chance = buf.readVarInt();
            if (i < MAX_IMPORT_LIST) {
                chances.add(chance);
            } else {
                staged[1] = 1;
            }
        }
        return chances;
    }

    public static void handle(final SetSequenceImportPacket packet, final ServerPlayer player) {
        final var menu = player.containerMenu;
        if (menu.containerId != packet.containerId() || !(menu instanceof SequencePatternTerminalMenu terminalMenu)) {
            return;
        }
        // 第 116 轮：整包体积 / 字符串预算 —— 在任何写入之前判定，超限<b>整包拒绝（原子）</b>：
        // 一格都不写、旧编排 / 旧原料保持原样，日志留证 + 动作栏告知。
        // 第 117 轮（复核 M2）：解码阶段若因暂存上限丢过任何一条，各列表之间已经互相错位
        // （候选组比输入少 / 流体被清空 / 概率与产物对不上）—— 导入它等于写入半张配方。
        // 这里必须整包拒绝并提示，绝不能「看起来成功」。
        if (packet.truncated()) {
            warnTooLarge("解码阶段被暂存上限截断（列表 4096 / 整包 8192 / 步数 1024）");
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "message.rs_create_compat.sequence.import_too_large"), true);
            return;
        }
        final String violation = budgetViolation(packet, player.level().registryAccess());
        if (violation != null) {
            warnTooLarge(violation);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "message.rs_create_compat.sequence.import_too_large"), true);
            return;
        }
        terminalMenu.importSequencedRecipe(packet.machines(), packet.stepInputs(), packet.stepCounts(),
            packet.loops(), packet.ingredient(), packet.results(), packet.resultChances(),
            packet.scraps(), packet.scrapChances(), packet.recipeId(), packet.stepFluids(),
            packet.stepCandidates(), packet.ingredientCandidates());
    }

    /**
     * 整包预算（第 116 轮）：<b>单条</b>物品 / 流体走 {@code MarkerBudget} 的同一把尺
     * （≈ 线上 2 KB），<b>合计</b>走 {@code MAX_IMPORT_TOTAL_BYTES}（≈ 线上 256 KB），
     * 客户端给的字符串（{@code machines} / 配方 id）另按 {@code MAX_IMPORT_STRING_LENGTH} 限长。
     *
     * <p><b>第 117 轮（独立复核 H1）</b>：配方 id 以前<b>完全没进预算</b>——而它会被
     * {@code SequencePatternData#TAG_RECIPE} 复制进<b>每一步</b>的单元样板，所以必须
     * ① 单条限长，② 按「步数」计入字符串合计。只按单条计一次会让「32 KB recipeId × 1024 步」
     * （每张样板各带一份）漏过去。</p>
     *
     * <p>这些数据最终都会写进方块实体 / 单元样板的 NBT 并随同步回发，所以判据放在<b>网络边界</b>
     * （解码之后、菜单写入之前）：宁可整包不做，也不能让「一半写进去、一半被拦」的中间态出现。</p>
     *
     * @return {@code null} = 通过；否则是「哪一项超了」的人类可读原因（只进日志，不给玩家看数字）
     */
    static String budgetViolation(final SetSequenceImportPacket packet,
                                  final net.minecraft.core.HolderLookup.Provider registries) {
        final java.util.List<net.minecraft.world.item.ItemStack> all = new java.util.ArrayList<>();
        appendStacks(all, packet.ingredient() == null ? null : java.util.List.of(packet.ingredient()));
        appendStacks(all, packet.ingredientCandidates());
        appendStacks(all, packet.stepInputs());
        appendStacks(all, packet.results());
        appendStacks(all, packet.scraps());
        if (packet.stepCandidates() != null) {
            for (final java.util.List<net.minecraft.world.item.ItemStack> group : packet.stepCandidates()) {
                appendStacks(all, group);
            }
        }
        long total = 0;
        for (final net.minecraft.world.item.ItemStack stack : all) {
            final int bytes = cretae.cookiewyq.rs_create_compat.support.MarkerBudget.stackBytes(stack, registries);
            if (cretae.cookiewyq.rs_create_compat.support.MarkerBudget.isEntryBytesTooBig(bytes)) {
                return "单条物品数据超限：" + bytes + " > "
                    + cretae.cookiewyq.rs_create_compat.support.MarkerBudget.MAX_ENTRY_BYTES
                    + "（" + stack.getItem() + "）";
            }
            total += bytes;
            if (total > cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity
                .MAX_IMPORT_TOTAL_BYTES) {
                return "物品合计超限：" + total + " > "
                    + cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity
                    .MAX_IMPORT_TOTAL_BYTES;
            }
        }
        if (packet.stepFluids() != null) {
            for (final net.neoforged.neoforge.fluids.FluidStack fluid : packet.stepFluids()) {
                final int bytes =
                    cretae.cookiewyq.rs_create_compat.support.MarkerBudget.fluidBytes(fluid, registries);
                if (cretae.cookiewyq.rs_create_compat.support.MarkerBudget.isEntryBytesTooBig(bytes)) {
                    return "单条流体数据超限：" + bytes;
                }
                total += bytes;
                if (total > cretae.cookiewyq.rs_create_compat.block.entity.SequencePatternTerminalBlockEntity
                    .MAX_IMPORT_TOTAL_BYTES) {
                    return "合计超限（含流体）：" + total;
                }
            }
        }
        // 字符串：machines 里每项都会写进单元样板的 CustomData（以前只受包长限制）
        long stringBytes = 0;
        for (final String machine : packet.machines()) {
            if (machine == null) {
                continue;
            }
            if (machine.length() > cretae.cookiewyq.rs_create_compat.block.entity
                .SequencePatternTerminalBlockEntity.MAX_IMPORT_STRING_LENGTH) {
                return "机器 / 配方类型字符串超长：" + machine.length();
            }
            stringBytes += 36L + 2L * machine.length(); // 与 CompoundTag#sizeInBytes 的字符串口径一致
            if (stringBytes > cretae.cookiewyq.rs_create_compat.block.entity
                .SequencePatternTerminalBlockEntity.MAX_IMPORT_TOTAL_BYTES) {
                return "步骤字符串合计超限：" + stringBytes;
            }
        }
        // 第 117 轮（复核 H1）：配方 id 以前<b>完全没进预算</b>，而它会被
        // {@code SequencePatternData#TAG_RECIPE} 逐张复制进<b>每一步</b>的单元样板
        // （菜单 → 单元样板 → 总样板 DisplayArrangement → 方块实体 NBT 并随同步回发）。
        // 改包发一个 32 KB 的 recipeId + 1024 个互不相同的短 machines（绕过 sameSteps 合并）
        // 就能让 1024 张样板各带 32 KB ⇒ 约 33 MB 落盘，而原来的 budgetViolation 返回 null。
        // 现在两道：① 单条限长（真实配方 id < 100 字符）；② 按「步数」计入字符串合计。
        final String recipeId = packet.recipeId() == null ? "" : packet.recipeId().toString();
        if (recipeId.length() > cretae.cookiewyq.rs_create_compat.block.entity
            .SequencePatternTerminalBlockEntity.MAX_IMPORT_STRING_LENGTH) {
            return "配方 id 超长：" + recipeId.length();
        }
        final int steps = packet.machines() == null ? 1 : Math.max(1, packet.machines().size());
        stringBytes += (36L + 2L * recipeId.length()) * steps;
        if (stringBytes > cretae.cookiewyq.rs_create_compat.block.entity
            .SequencePatternTerminalBlockEntity.MAX_IMPORT_TOTAL_BYTES) {
            return "字符串合计超限（含配方 id × 步数）：" + stringBytes;
        }
        return null;
    }

    private static void appendStacks(final java.util.List<net.minecraft.world.item.ItemStack> target,
                                     final java.util.List<net.minecraft.world.item.ItemStack> source) {
        if (source == null) {
            return;
        }
        for (final net.minecraft.world.item.ItemStack stack : source) {
            if (stack != null && !stack.isEmpty()) {
                target.add(stack);
            }
        }
    }

    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger("rs_create_compat/SetSequenceImportPacket");

    /** 「导入被拒」的 WARN：每进程只打一条（改包者刷包不会刷日志），但原因都写全。 */
    private static boolean tooLargeWarned;

    private static void warnTooLarge(final String violation) {
        if (!tooLargeWarned) {
            tooLargeWarned = true;
            LOGGER.warn("[rs_create_compat] 序列终端：拒绝了一次配方导入 —— {}（本次未写入任何一格；"
                + "正常 JEI 转移不会触发，若是改包，本条即证据）", violation);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
