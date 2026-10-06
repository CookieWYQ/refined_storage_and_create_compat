package cretae.cookiewyq.rs_create_compat.support;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 多条目流体 / 气体缓存：<b>总容量有上限、种类无上限</b>。
 * <p>用于归流缓存仓的流体缓存（512000 mB），也可被自动合成仓内部存储复用
 * （把原来的单种类 {@code FluidTank} 换成本类即可获得「种类无上限 + 容量上限」语义）。
 * <p>条目键 = （流体注册名 + 数据组件 NBT），容量守恒：所有 insert/extract 都以 mB 累加/扣减，
 * 任何时刻 {@code getStored() <= getCapacity()}。
 */
public final class MultiFluidCache {
    private static final String TAG_ID = "Id";
    private static final String TAG_NBT = "Nbt";
    private static final String TAG_AMOUNT = "Amount";

    /** 一条流体条目（不可变）。 */
    public record Entry(ResourceLocation id, CompoundTag nbt, long amount) {
    }

    private final long capacity;
    private final List<Entry> entries = new ArrayList<>();
    private long stored;

    public MultiFluidCache(final long capacity) {
        this.capacity = Math.max(0L, capacity);
    }

    public long getCapacity() {
        return capacity;
    }

    /** 已存总量（mB）。 */
    public long getStored() {
        return stored;
    }

    /** 剩余空间（mB，恒 >= 0）。 */
    public long getFreeSpace() {
        return Math.max(0L, capacity - stored);
    }

    /** 当前条目数（= 资源种类数，无上限）。 */
    public int getKinds() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** 条目快照（只读，遍历时不受后续修改影响）。 */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** 查询某资源当前存量（mB）。 */
    public long getAmount(final ResourceLocation id, @Nullable final CompoundTag nbt) {
        if (id == null) {
            return 0L;
        }
        final CompoundTag key = nbt == null ? new CompoundTag() : nbt;
        for (final Entry entry : entries) {
            if (entry.id().equals(id) && entry.nbt().equals(key)) {
                return entry.amount();
            }
        }
        return 0L;
    }

    /** 存入，返回实际接收量（<= amount；容量满返回 0）。 */
    public long insert(@Nullable final ResourceLocation id, @Nullable final CompoundTag nbt, final long amount) {
        if (id == null || amount <= 0L) {
            return 0L;
        }
        final long accepted = Math.min(getFreeSpace(), amount);
        if (accepted <= 0L) {
            return 0L;
        }
        final CompoundTag key = nbt == null ? new CompoundTag() : nbt;
        for (int i = 0; i < entries.size(); i++) {
            final Entry entry = entries.get(i);
            if (entry.id().equals(id) && entry.nbt().equals(key)) {
                entries.set(i, new Entry(id, entry.nbt(), entry.amount() + accepted));
                stored += accepted;
                return accepted;
            }
        }
        entries.add(new Entry(id, key.copy(), accepted));
        stored += accepted;
        return accepted;
    }

    /** 取出，返回实际取出量（<= amount）。 */
    public long extract(@Nullable final ResourceLocation id, @Nullable final CompoundTag nbt, final long amount) {
        if (id == null || amount <= 0L) {
            return 0L;
        }
        final CompoundTag key = nbt == null ? new CompoundTag() : nbt;
        for (int i = 0; i < entries.size(); i++) {
            final Entry entry = entries.get(i);
            if (!entry.id().equals(id) || !entry.nbt().equals(key)) {
                continue;
            }
            final long extracted = Math.min(entry.amount(), amount);
            if (extracted <= 0L) {
                return 0L;
            }
            if (entry.amount() - extracted <= 0L) {
                entries.remove(i);
            } else {
                entries.set(i, new Entry(id, entry.nbt(), entry.amount() - extracted));
            }
            stored -= extracted;
            return extracted;
        }
        return 0L;
    }

    public void clear() {
        entries.clear();
        stored = 0L;
    }

    /** 写入指定 tag 键（ListTag，只存非空条目）。 */
    public void save(final CompoundTag parent, final String key) {
        final ListTag list = new ListTag();
        for (final Entry entry : entries) {
            final CompoundTag entryTag = new CompoundTag();
            entryTag.putString(TAG_ID, entry.id().toString());
            entryTag.put(TAG_NBT, entry.nbt());
            entryTag.putLong(TAG_AMOUNT, entry.amount());
            list.add(entryTag);
        }
        parent.put(key, list);
    }

    /** 从指定 tag 键读取；条目总和不越过容量上限（改小配置后按序截断，保证容量守恒）。 */
    public void load(final CompoundTag parent, final String key) {
        entries.clear();
        stored = 0L;
        final ListTag list = parent.getList(key, Tag.TAG_COMPOUND);
        long remaining = capacity;
        for (int i = 0; i < list.size() && remaining > 0L; i++) {
            final CompoundTag entryTag = list.getCompound(i);
            final ResourceLocation id = ResourceLocation.tryParse(entryTag.getString(TAG_ID));
            if (id == null) {
                continue;
            }
            final long amount = Math.min(remaining, Math.max(0L, entryTag.getLong(TAG_AMOUNT)));
            if (amount <= 0L) {
                continue;
            }
            final CompoundTag nbt = entryTag.contains(TAG_NBT) ? entryTag.getCompound(TAG_NBT) : new CompoundTag();
            entries.add(new Entry(id, nbt, amount));
            remaining -= amount;
        }
        stored = capacity - remaining;
    }
}
