package net.loserver.oreradar.core.util;

/**
 * Open-addressing set of longs (packed block positions), so the outline merger does not box a
 * {@code Long} per lookup. Not thread-safe.
 */
public final class LongHashSet {
    private long[] keys;
    private int mask;
    private int size;
    /** 0 marks a free slot in {@link #keys}, so the key 0 (block 0,0,0) is tracked separately. */
    private boolean hasZero;

    public LongHashSet(int expectedSize) {
        int capacity = Integer.highestOneBit(Math.max(4, expectedSize) * 2 - 1) << 1;
        keys = new long[capacity];
        mask = capacity - 1;
    }

    public LongHashSet(long[] values) {
        this(values.length);
        for (long v : values) add(v);
    }

    /** Adds the key; false if it was already present. */
    public boolean add(long key) {
        if (key == 0) {
            if (hasZero) return false;
            hasZero = true;
            size++;
            return true;
        }
        int i = slot(key);
        while (keys[i] != 0) {
            if (keys[i] == key) return false;
            i = (i + 1) & mask;
        }
        keys[i] = key;
        if (++size * 2 > keys.length) grow();
        return true;
    }

    public boolean contains(long key) {
        if (key == 0) return hasZero;
        int i = slot(key);
        while (keys[i] != 0) {
            if (keys[i] == key) return true;
            i = (i + 1) & mask;
        }
        return false;
    }

    public int size() {
        return size;
    }

    private int slot(long key) {
        // MurmurHash3 finaliser: packed positions differ mostly in a few bit ranges.
        long h = key * 0x9E3779B97F4A7C15L;
        h ^= h >>> 32;
        h ^= h >>> 16;
        return (int) h & mask;
    }

    private void grow() {
        long[] old = keys;
        keys = new long[old.length * 2];
        mask = keys.length - 1;
        for (long k : old) {
            if (k == 0) continue;
            int i = slot(k);
            while (keys[i] != 0) i = (i + 1) & mask;
            keys[i] = k;
        }
    }
}
