package cretae.cookiewyq.rs_create_compat.compat.crafter;

import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlock;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import com.simibubi.create.content.kinetics.crafter.MechanicalCraftingRecipe;
import com.simibubi.create.content.kinetics.crafter.RecipeGridHandler;
import net.createmod.catnip.math.Pointing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「Create 动力合成器（Mechanical Crafter）按位置喂料」的<b>计划与执行器</b>。
 *
 * <h2>为什么需要它（不能靠公开 sink 工厂）</h2>
 * <p>RS 通用 item sink 是「按第一个空槽顺序塞」（build/rssrc/.../neoforge/autocrafting/
 * ItemHandlerExternalPatternProviderSink.java:96-123），而动力合成器的物品能力是
 * {@code ConnectedInput#getItemHandler} 的 {@code CombinedInvWrapper}（按位置排序的整列背包，
 * build/createsrc/.../crafter/ConnectedInputHandler.java:178-214）——喂进去的物品会按排序顺序
 * 落到格子里，与样板/配方要求的<b>位置</b>无关。所以必须由本模组自己算出「哪一件进哪一格」，
 * 逐个塞进对应 crafter 的背包（{@code MechanicalCrafterBlockEntity#getInventory()}，public）。</p>
 *
 * <h2>格位是怎么算出来的（纯拓扑推导，不读私有字段）</h2>
 * <p>Create 的合成阵列在装配时，每台 crafter 把自己那一格合并给「目标方向」上的那台，
 * 合并偏移由<b>自己</b>方块的 {@code POINTING} 属性决定
 * （{@code GroupedItems.mergeOnto}，build/createsrc/.../RecipeGridHandler.java:188-194：
 * LEFT→x+1、RIGHT→x-1、DOWN→y+1、UP→y-1；调用点 :334-346）。</p>
 * <p>于是某台 crafter 的物品在最终网格里的坐标是<b>沿「自己 → 链头」这条唯一路径的偏移之和</b>
 * （不含链头自己的偏移，因为链头不再合并出去）：</p>
 * <pre>g(X) = Σ offset(N.POINTING)，N 从 X 起、沿 target 关系向上，直到 root 之前</pre>
 * <p>链头（root）= 没有目标 crafter 的那台。这套推导只用 public API：
 * {@link RecipeGridHandler#getTargetingCrafter}、{@link RecipeGridHandler#getPrecedingCrafters}、
 * {@link MechanicalCrafterBlock#getTargetDirection}、{@link MechanicalCrafterBlock#POINTING}。</p>
 *
 * <h2>配方坐标系</h2>
 * <p>Create 把网格交给配方时会把 y 轴翻过来（{@code MechanicalCraftingInput.of}，
 * build/createsrc/.../MechanicalCraftingInput.java:17-57：行 {@code y} 取 {@code maxY - y}），
 * 并且只保留<b>非空</b>包围盒。因此：
 * <ol>
 *     <li>配方第 0 行 = 网格最上方（y 最大）那一行；</li>
 *     <li>配方图案不能有空白的首/末行或首/末列，否则输入包围盒会缩小、维数对不上 ⇒
 *     真正的机械合成也永远匹配不上（这是 Create 自身性质，我们只是跳过这类配方）；</li>
 *     <li>配方非空格与阵列格位一一对应，逐格插 1 件即可（每台 crafter 的背包 1 格、
 *     叠加上限 1，见 {@code MechanicalCrafterBlockEntity.java:59-88}）。</li>
 * </ol>
 */
public final class MechanicalCraftPlan {
    /** 单次派发的最大件数（格位数量上限，防止极端配方把匹配搜索放大）。 */
    private static final int MAX_UNITS = 36;
    /** 阵列规模上限（防御异常拓扑）。 */
    private static final int MAX_ARRAY = 64;
    /** 拓扑推导的环保护深度。 */
    private static final int MAX_DEPTH = 64;
    /** 计划缓存的有效期（tick）：只为省掉「每次 SIMULATE 都全量扫配方」。 */
    private static final int CACHE_TTL_TICKS = 100;

    private final ServerLevel level;
    private final List<BlockPos> members;
    private final Map<BlockPos, ItemStack> placements;
    private final ItemStack mainResult;
    private final List<ItemStack> expectedOutputs;

    private MechanicalCraftPlan(final ServerLevel level,
                                final List<BlockPos> members,
                                final Map<BlockPos, ItemStack> placements,
                                final ItemStack mainResult,
                                final List<ItemStack> expectedOutputs) {
        this.level = level;
        this.members = members;
        this.placements = placements;
        this.mainResult = mainResult;
        this.expectedOutputs = expectedOutputs;
    }

    /** 阵列全部成员坐标（供产物回收登记）。 */
    public List<BlockPos> members() {
        return members;
    }

    /** 本次合成的「主产物」（掉落时用来精确匹配）。 */
    public ItemStack mainResult() {
        return mainResult;
    }

    /** 期望产物 = 主产物 + 配方剩余物（例如空桶）。 */
    public List<ItemStack> expectedOutputs() {
        return expectedOutputs;
    }

    /**
     * 为一次派发构造计划。
     *
     * @return {@code null} 表示「本模组不适用」——调用方必须<b>原样退回 RS 原语义</b>，
     * 而不是自己拒绝（不吞料、不复制）
     */
    @Nullable
    public static MechanicalCraftPlan find(final ServerLevel level,
                                           final MechanicalCrafterBlockEntity anchor,
                                           final Collection<ResourceAmount> resources,
                                           @Nullable final Pattern pattern) {
        final List<ItemStack> units = expandUnits(resources);
        if (units == null) {
            return null;
        }
        final Map<BlockPos, MechanicalCrafterBlockEntity> array = collectArray(level, anchor);
        if (array == null || array.isEmpty() || array.size() > MAX_ARRAY) {
            return null;
        }
        final Map<BlockPos, int[]> coordinates = gridCoordinates(array);
        if (coordinates == null) {
            return null;
        }
        final String signature = signatureOf(units);
        final Cached cached = lookup(level, anchor.getBlockPos(), signature);
        if (cached != null) {
            if (cached.assignment == null) {
                return null; // 负缓存：这段时间内不必再全量扫一遍配方
            }
            return new MechanicalCraftPlan(level, cached.assignment.members(), cached.assignment.placements(),
                cached.assignment.mainResult(), cached.assignment.expectedOutputs());
        }
        final Assignment assignment = solve(level, array, coordinates, units, pattern);
        store(level, anchor.getBlockPos(), signature, assignment);
        if (assignment == null) {
            return null;
        }
        return new MechanicalCraftPlan(level, assignment.members, assignment.placements,
            assignment.mainResult, assignment.expectedOutputs);
    }

    /**
     * 只读模拟：确认「所有目标格现在都能塞进对应的那一件、且阵列其余格是空的/有盖板」。
     *
     * <p><b>无副作用保证</b>：这里全部走 {@code insertItem(..., simulate = true)}，
     * 不修改任何机器状态；{@link #apply()} 之前必须有一次成功的 {@code canApply()}。</p>
     */
    public boolean canApply() {
        for (final Map.Entry<BlockPos, ItemStack> entry : placements.entrySet()) {
            final MechanicalCrafterBlockEntity crafter = crafterAt(entry.getKey());
            if (crafter == null) {
                return false;
            }
            // 背包插入的拒绝条件（phase != IDLE / covered / 已有物品）由 Create 自己判定，
            // 这里只做模拟，不碰状态。
            if (!crafter.getInventory().insertItem(0, entry.getValue(), true).isEmpty()) {
                return false;
            }
        }
        for (final BlockPos memberPos : members) {
            if (placements.containsKey(memberPos)) {
                continue;
            }
            final MechanicalCrafterBlockEntity crafter = crafterAt(memberPos);
            if (crafter == null) {
                return false;
            }
            // 不参与本次配方的格子：必须是空的，并且「有物品或有盖板」——否则 Create 的
            // getAllCraftersOfChainIf 会因为「有格子既没物品也没盖板」而拒绝开合
            // （build/createsrc/.../crafter/RecipeGridHandler.java:49-83）。
            if (!crafter.getInventory().getStackInSlot(0).isEmpty()) {
                return false;
            }
            if (!crafter.craftingItemOrCoverPresent()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 真正落料：逐格插入（每格 1 件）。
     *
     * <p>调用方必须已经用 {@link #canApply()} 模拟通过；若这里仍然失败（理论上只可能发生在
     * 同 tick 内被别的逻辑改动机器），返回 {@code false} 且记一条节流警告——绝不「假装成功」。</p>
     */
    public boolean apply() {
        boolean ok = true;
        for (final Map.Entry<BlockPos, ItemStack> entry : placements.entrySet()) {
            final MechanicalCrafterBlockEntity crafter = crafterAt(entry.getKey());
            if (crafter == null) {
                ok = false;
                continue;
            }
            final ItemStack remainder = crafter.getInventory().insertItem(0, entry.getValue(), false);
            if (!remainder.isEmpty()) {
                ok = false;
            }
        }
        if (!ok) {
            CreateCraftLog.warn("动力合成器落料出现残余（计划 {} 格）：机器可能刚被别的逻辑改动，"
                + "本次派发不完整，交由 RS 原语义下次重试", placements.size());
        }
        return ok;
    }

    @Nullable
    private MechanicalCrafterBlockEntity crafterAt(final BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof MechanicalCrafterBlockEntity crafter ? crafter : null;
    }

    // ------------------------------------------------------------------
    // 资源展开
    // ------------------------------------------------------------------

    @Nullable
    private static List<ItemStack> expandUnits(final Collection<ResourceAmount> resources) {
        final List<ItemStack> units = new ArrayList<>();
        for (final ResourceAmount resourceAmount : resources) {
            if (!(resourceAmount.resource() instanceof ItemResource itemResource)) {
                return null; // 含流体的样板不归本类处理（原语义保留）
            }
            final long amount = resourceAmount.amount();
            if (amount <= 0L || amount > MAX_UNITS) {
                return null;
            }
            final ItemStack single = itemResource.toItemStack(1);
            for (long i = 0L; i < amount; i++) {
                units.add(single.copy());
            }
        }
        if (units.isEmpty() || units.size() > MAX_UNITS) {
            return null;
        }
        return units;
    }

    // ------------------------------------------------------------------
    // 阵列拓扑 → 格位
    // ------------------------------------------------------------------

    @Nullable
    private static Map<BlockPos, MechanicalCrafterBlockEntity> collectArray(
        final ServerLevel level, final MechanicalCrafterBlockEntity anchor) {
        final Map<BlockPos, MechanicalCrafterBlockEntity> array = new LinkedHashMap<>();
        final Deque<MechanicalCrafterBlockEntity> queue = new ArrayDeque<>();
        queue.add(anchor);
        array.put(anchor.getBlockPos(), anchor);
        while (!queue.isEmpty()) {
            final MechanicalCrafterBlockEntity current = queue.poll();
            for (final MechanicalCrafterBlockEntity next : neighboursOf(current)) {
                if (array.size() >= MAX_ARRAY) {
                    return null;
                }
                if (array.putIfAbsent(next.getBlockPos(), next) == null) {
                    queue.add(next);
                }
            }
        }
        return array;
    }

    private static List<MechanicalCrafterBlockEntity> neighboursOf(final MechanicalCrafterBlockEntity crafter) {
        final List<MechanicalCrafterBlockEntity> neighbours = new ArrayList<>(3);
        final MechanicalCrafterBlockEntity target = RecipeGridHandler.getTargetingCrafter(crafter);
        if (target != null) {
            neighbours.add(target);
        }
        final List<MechanicalCrafterBlockEntity> preceding = RecipeGridHandler.getPrecedingCrafters(crafter);
        if (preceding != null) {
            neighbours.addAll(preceding);
        }
        return neighbours;
    }

    /**
     * 计算每台 crafter 在最终网格里的坐标（见类 javadoc 的 g(X) 公式）。
     *
     * @return {@code null} = 拓扑异常（成环 / 深度超限）
     */
    @Nullable
    private static Map<BlockPos, int[]> gridCoordinates(
        final Map<BlockPos, MechanicalCrafterBlockEntity> array) {
        final Map<BlockPos, int[]> result = new HashMap<>();
        for (final MechanicalCrafterBlockEntity crafter : array.values()) {
            int x = 0;
            int y = 0;
            MechanicalCrafterBlockEntity current = crafter;
            final Set<BlockPos> visited = new HashSet<>();
            for (int depth = 0; depth < MAX_DEPTH; depth++) {
                if (!visited.add(current.getBlockPos())) {
                    return null;
                }
                final MechanicalCrafterBlockEntity parent = RecipeGridHandler.getTargetingCrafter(current);
                if (parent == null) {
                    break;
                }
                final int[] offset = pointerOffset(current.getBlockState());
                x += offset[0];
                y += offset[1];
                current = parent;
            }
            result.put(crafter.getBlockPos(), new int[] {x, y});
        }
        return result;
    }

    /** {@code POINTING} → 合并偏移（与 GroupedItems#mergeOnto 完全一致）。 */
    private static int[] pointerOffset(final BlockState state) {
        if (!state.hasProperty(MechanicalCrafterBlock.POINTING)) {
            return new int[] {0, 0};
        }
        final Pointing pointing = state.getValue(MechanicalCrafterBlock.POINTING);
        return switch (pointing) {
            case LEFT -> new int[] {1, 0};
            case RIGHT -> new int[] {-1, 0};
            case DOWN -> new int[] {0, 1};
            case UP -> new int[] {0, -1};
        };
    }

    // ------------------------------------------------------------------
    // 配方反查 + 格位分配
    // ------------------------------------------------------------------

    @Nullable
    private static Assignment solve(final ServerLevel level,
                                    final Map<BlockPos, MechanicalCrafterBlockEntity> array,
                                    final Map<BlockPos, int[]> coordinates,
                                    final List<ItemStack> units,
                                    @Nullable final Pattern pattern) {
        // 第一遍：优先选「产物出现在样板输出里」的配方（避免同多重集有多个配方时选错）；
        // 第二遍：退而求其次，接受任意一个能对上图案的配方。
        final Assignment preferred = scan(level, array, coordinates, units, pattern, true);
        if (preferred != null) {
            return preferred;
        }
        return scan(level, array, coordinates, units, pattern, false);
    }

    @Nullable
    private static Assignment scan(final ServerLevel level,
                                   final Map<BlockPos, MechanicalCrafterBlockEntity> array,
                                   final Map<BlockPos, int[]> coordinates,
                                   final List<ItemStack> units,
                                   @Nullable final Pattern pattern,
                                   final boolean requirePatternOutput) {
        // 坐标 → 位置 的反查表
        final Map<Long, BlockPos> byCoordinate = new HashMap<>();
        for (final Map.Entry<BlockPos, int[]> entry : coordinates.entrySet()) {
            byCoordinate.put(coordinateKey(entry.getValue()[0], entry.getValue()[1]), entry.getKey());
        }
        final List<BlockPos> members = new ArrayList<>(array.keySet());
        for (final RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            final Recipe<?> recipe = holder.value();
            if (!(recipe instanceof ShapedRecipe shaped)) {
                continue;
            }
            final boolean mechanical = recipe instanceof MechanicalCraftingRecipe;
            if (mechanical) {
                if (recipe.getType() != AllRecipeTypes.MECHANICAL_CRAFTING.getType()) {
                    continue;
                }
            } else {
                if (recipe.getType() != RecipeType.CRAFTING) {
                    continue;
                }
                // 与 Create 自身 tryToApplyRecipe 的过滤一致（RecipeGridHandler.java:142-175）
                if (AllRecipeTypes.shouldIgnoreInAutomation(holder)) {
                    continue;
                }
            }
            final NonNullList<Ingredient> ingredients = shaped.getIngredients();
            final int width = shaped.getWidth();
            final int height = shaped.getHeight();
            if (width <= 0 || height <= 0 || ingredients.size() != width * height) {
                continue;
            }
            int nonEmpty = 0;
            for (final Ingredient ingredient : ingredients) {
                if (!ingredient.isEmpty()) {
                    nonEmpty++;
                }
            }
            if (nonEmpty != units.size()) {
                continue;
            }
            if (!patternOutputMatches(pattern, shaped, level, requirePatternOutput)) {
                continue;
            }
            final Assignment assignment = place(ingredients, width, height,
                coordinates, byCoordinate, members, units, shaped, level);
            if (assignment != null) {
                return assignment;
            }
        }
        return null;
    }

    private static boolean patternOutputMatches(@Nullable final Pattern pattern,
                                                final ShapedRecipe shaped,
                                                final ServerLevel level,
                                                final boolean requirePatternOutput) {
        if (!requirePatternOutput || pattern == null) {
            return true;
        }
        final ItemStack result = shaped.getResultItem(level.registryAccess());
        if (result.isEmpty()) {
            return false;
        }
        final ResourceKey resultKey = ItemResource.ofItemStack(result);
        for (final ResourceAmount output : pattern.layout().outputs()) {
            if (output.resource().equals(resultKey)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static Assignment place(final NonNullList<Ingredient> ingredients,
                                    final int width,
                                    final int height,
                                    final Map<BlockPos, int[]> coordinates,
                                    final Map<Long, BlockPos> byCoordinate,
                                    final List<BlockPos> members,
                                    final List<ItemStack> units,
                                    final ShapedRecipe shaped,
                                    final ServerLevel level) {
        // 非空格清单
        final List<Cell> cells = new ArrayList<>();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int ry = 0; ry < height; ry++) {
            for (int rx = 0; rx < width; rx++) {
                final Ingredient ingredient = ingredients.get(rx + ry * width);
                if (ingredient.isEmpty()) {
                    continue;
                }
                cells.add(new Cell(rx, ry, ingredient));
                minX = Math.min(minX, rx);
                maxX = Math.max(maxX, rx);
                minY = Math.min(minY, ry);
                maxY = Math.max(maxY, ry);
            }
        }
        if (cells.isEmpty() || cells.size() != units.size()) {
            return null;
        }
        // 图案必须「紧」：有空白的首/末行或列时，Create 的输入包围盒会缩水，
        // 配方永远匹配不上（见类 javadoc 第 2 条）。
        if (minX != 0 || minY != 0 || maxX != width - 1 || maxY != height - 1) {
            return null;
        }
        // 枚举「图案左上角」落在阵列的哪一格
        final List<int[]> origins = new ArrayList<>();
        for (final BlockPos pos : members) {
            final int[] coordinate = coordinates.get(pos);
            if (coordinate != null) {
                origins.add(coordinate);
            }
        }
        origins.sort((a, b) -> a[1] != b[1] ? Integer.compare(b[1], a[1]) : Integer.compare(a[0], b[0]));
        final int[] unitToCell = new int[units.size()];
        final boolean[] cellUsed = new boolean[cells.size()];
        for (final int[] origin : origins) {
            Arrays.fill(unitToCell, -1);
            Arrays.fill(cellUsed, false);
            // 每个配方格 → 阵列坐标（行 0 = 最上方 ⇒ 网格 y = originY - ry）
            final BlockPos[] cellPositions = new BlockPos[cells.size()];
            boolean fits = true;
            for (int i = 0; i < cells.size(); i++) {
                final Cell cell = cells.get(i);
                final int gridX = origin[0] + cell.x;
                final int gridY = origin[1] - cell.y;
                final BlockPos pos = byCoordinate.get(coordinateKey(gridX, gridY));
                if (pos == null) {
                    fits = false;
                    break;
                }
                cellPositions[i] = pos;
            }
            if (!fits) {
                continue;
            }
            if (!assignUnits(units, cells, unitToCell, cellUsed, 0)) {
                continue;
            }
            final Map<BlockPos, ItemStack> placements = new LinkedHashMap<>();
            final List<ItemStack> inputGrid = new ArrayList<>(width * height);
            for (int i = 0; i < width * height; i++) {
                inputGrid.add(ItemStack.EMPTY);
            }
            for (int i = 0; i < units.size(); i++) {
                final Cell cell = cells.get(unitToCell[i]);
                final ItemStack unit = units.get(i).copyWithCount(1);
                placements.put(cellPositions[unitToCell[i]], unit);
                inputGrid.set(cell.x + cell.y * width, unit.copy());
            }
            if (placements.size() != units.size()) {
                return null;
            }
            final ItemStack mainResult = shaped.getResultItem(level.registryAccess()).copy();
            if (mainResult.isEmpty()) {
                return null;
            }
            final List<ItemStack> expected = new ArrayList<>();
            expected.add(mainResult.copy());
            final CraftingInput craftingInput = CraftingInput.of(width, height, inputGrid);
            for (final ItemStack remaining : shaped.getRemainingItems(craftingInput)) {
                if (!remaining.isEmpty()) {
                    expected.add(remaining.copy());
                }
            }
            return new Assignment(members, placements, mainResult, expected);
        }
        return null;
    }

    /** 二分图匹配：把每一件物品分配给一个「Ingredient 认它」的配方格。 */
    private static boolean assignUnits(final List<ItemStack> units,
                                       final List<Cell> cells,
                                       final int[] unitToCell,
                                       final boolean[] cellUsed,
                                       final int index) {
        if (index == units.size()) {
            return true;
        }
        final ItemStack unit = units.get(index);
        for (int cell = 0; cell < cells.size(); cell++) {
            if (cellUsed[cell] || !cells.get(cell).ingredient.test(unit)) {
                continue;
            }
            cellUsed[cell] = true;
            unitToCell[index] = cell;
            if (assignUnits(units, cells, unitToCell, cellUsed, index + 1)) {
                return true;
            }
            cellUsed[cell] = false;
            unitToCell[index] = -1;
        }
        return false;
    }

    private static long coordinateKey(final int x, final int y) {
        return ((long) x << 32) ^ (y & 0xFFFFFFFFL);
    }

    private static String signatureOf(final List<ItemStack> units) {
        final List<String> parts = new ArrayList<>(units.size());
        for (final ItemStack unit : units) {
            parts.add(unit.getItem() + "@" + unit.getComponentsPatch().hashCode());
        }
        parts.sort(String::compareTo);
        return String.join(",", parts);
    }

    private record Cell(int x, int y, Ingredient ingredient) {
    }

    private record Assignment(List<BlockPos> members,
                              Map<BlockPos, ItemStack> placements,
                              ItemStack mainResult,
                              List<ItemStack> expectedOutputs) {
    }

    /** 缓存项：{@code assignment == null} 表示「负缓存」（这一批资源在该阵列上没有可用图案）。 */
    private record Cached(@Nullable Assignment assignment, long expiresAt) {
    }

    // ------------------------------------------------------------------
    // 计划缓存（只为省掉重复的配方全量扫描）
    // ------------------------------------------------------------------

    private static final Map<ServerLevel, Map<String, Cached>> CACHE = new java.util.WeakHashMap<>();

    private static String cacheKey(final BlockPos anchor, final String signature) {
        return anchor.asLong() + "#" + signature;
    }

    @Nullable
    private static Cached lookup(final ServerLevel level, final BlockPos anchor, final String signature) {
        final Map<String, Cached> levelCache = CACHE.get(level);
        if (levelCache == null) {
            return null;
        }
        final String key = cacheKey(anchor, signature);
        final Cached cached = levelCache.get(key);
        if (cached == null) {
            return null;
        }
        if (level.getGameTime() > cached.expiresAt) {
            levelCache.remove(key);
            return null;
        }
        return cached;
    }

    private static void store(final ServerLevel level,
                              final BlockPos anchor,
                              final String signature,
                              @Nullable final Assignment assignment) {
        final Map<String, Cached> levelCache = CACHE.computeIfAbsent(level, key -> new HashMap<>());
        levelCache.put(cacheKey(anchor, signature),
            new Cached(assignment, level.getGameTime() + CACHE_TTL_TICKS));
    }

    /** 丢弃缓存（方块/机器变化时由外部调用，避免陈旧计划）。 */
    public static void invalidate(final ServerLevel level) {
        CACHE.remove(level);
    }
}
